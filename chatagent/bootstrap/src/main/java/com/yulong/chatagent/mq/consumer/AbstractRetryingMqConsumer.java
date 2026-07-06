package com.yulong.chatagent.mq.consumer;

import com.rabbitmq.client.Channel;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.lock.DistributedLockManager;
import com.yulong.chatagent.mq.lock.LockWatchdog;
import com.yulong.chatagent.mq.lock.MqSessionExecLockAcquisition;
import com.yulong.chatagent.mq.lock.MqSessionExecLockLease;
import com.yulong.chatagent.mq.lock.MqTaskLockAcquireOutcome;
import com.yulong.chatagent.mq.lock.MqTaskLockAcquisition;
import com.yulong.chatagent.mq.lock.MqTaskLockLease;
import com.yulong.chatagent.mq.support.MqMessageHeaders;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.mq.support.RabbitMqMessagePublisher;
import com.yulong.chatagent.ratelimit.capacity.CapacityGateResult;
import com.yulong.chatagent.ratelimit.capacity.NoopPermit;
import com.yulong.chatagent.ratelimit.capacity.Permit;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * MQ consumer 的通用模板。
 *
 * AgentRunTaskListener 和 KnowledgeIngestTaskListener 都复用这套流程：
 * 1. 从 header 读取 MqMessageIdentity；
 * 2. 用 task lock 做幂等去重；
 * 3. 需要时用 session exec lock 保证同一 session 串行执行；
 * 4. 启动 watchdog 续租；
 * 5. 调用子类真正处理业务；
 * 6. 成功 ack，失败按 retry/DLQ 策略处理。
 */
@Slf4j
public abstract class AbstractRetryingMqConsumer<T> {

    /**
     * Sentinel returned by {@link #handleCapacityWait} when the wait has timed
     * out and the caller should complete the turn and ACK instead of requeuing.
     */
    protected static final MqMessageIdentity TERMINAL_CAPACITY_WAIT = null;

    private final ChatAgentMqProperties properties;
    private final RabbitMqMessagePublisher rabbitMqMessagePublisher;
    private final DistributedLockManager distributedLockManager;
    private final LockWatchdog lockWatchdog;
    private final String ownerId;
    private final Clock clock;

    protected AbstractRetryingMqConsumer(ChatAgentMqProperties properties,
                                         RabbitMqMessagePublisher rabbitMqMessagePublisher,
                                         DistributedLockManager distributedLockManager,
                                         LockWatchdog lockWatchdog) {
        this(properties, rabbitMqMessagePublisher, distributedLockManager, lockWatchdog, Clock.systemUTC());
    }

    AbstractRetryingMqConsumer(ChatAgentMqProperties properties,
                               RabbitMqMessagePublisher rabbitMqMessagePublisher,
                               DistributedLockManager distributedLockManager,
                               LockWatchdog lockWatchdog,
                               Clock clock) {
        this.properties = properties;
        this.rabbitMqMessagePublisher = rabbitMqMessagePublisher;
        this.distributedLockManager = distributedLockManager;
        this.lockWatchdog = lockWatchdog;
        this.clock = clock;
        this.ownerId = consumerName() + ":" + UUID.randomUUID();
    }

    protected final void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        MqMessageIdentity identity = MqMessageHeaders.read(message.getMessageProperties());
        // traceId 从 MQ header 透传到当前线程日志，方便串起 outbox -> consumer -> runtime。
        TraceContext.setTraceId(identity.traceId());
        try {
            MqTaskLockAcquisition acquisition;
            try {
                // 第一把锁：task lock。key = taskType + idempotencyKey，用于识别同一个业务任务的重复投递。
                acquisition = distributedLockManager.tryAcquire(identity, ownerId);
            } catch (Exception acquisitionException) {
                ChatAgentMqProperties.RedisFailurePolicy policy =
                        properties.getLocks().getPolicyForTask(identity.taskType());
                if (policy == ChatAgentMqProperties.RedisFailurePolicy.FAIL_OPEN) {
                    log.warn("Redis failure during tryAcquire, continuing without idempotency: taskType={}, eventId={}, error={}",
                            identity.taskType(), identity.eventId(), acquisitionException.getMessage());
                    handleOwnedMessage(message, channel, deliveryTag, identity, null, null);
                    return;
                }
                log.warn("Redis failure during tryAcquire, requeueing because policy is FAIL_FAST: taskType={}, eventId={}, error={}",
                        identity.taskType(), identity.eventId(), acquisitionException.getMessage());
                channel.basicNack(deliveryTag, false, true);
                return;
            }
            if (acquisition.outcome() == MqTaskLockAcquireOutcome.WAIT_REQUIRED) {
                // 已有同一个任务处于 RUNNING。将消息放入延迟重投队列，等当前任务完成后再处理。
                requeueWithDelay(message, channel, deliveryTag, identity, "task lock already RUNNING");
                return;
            }
            if (acquisition.outcome() == MqTaskLockAcquireOutcome.DUPLICATE) {
                // COMPLETED 等终态说明这个业务任务已经处理过，重复消息直接 ack。
                log.info("MQ task skipped as duplicate: taskType={}, eventId={}, state={}",
                        identity.taskType(), identity.eventId(), acquisition.existingState());
                channel.basicAck(deliveryTag, false);
                return;
            }
            MqTaskLockLease taskLease = acquisition.lease();
            // 第二把锁：session execution lock。它不是幂等锁，而是保证同一 session 同时只跑一个 Agent。
            SessionAcquireResult sessionAcquireResult = acquireSessionExecLockOrHandle(
                    message,
                    channel,
                    deliveryTag,
                    identity,
                    taskLease
            );
            if (sessionAcquireResult.handled()) {
                return;
            }
            MqSessionExecLockLease sessionLease = sessionAcquireResult.lease();
            handleOwnedMessage(message, channel, deliveryTag, identity, taskLease, sessionLease);
        } finally {
            TraceContext.clear();
        }
    }

    private void handleOwnedMessage(Message message,
                                    Channel channel,
                                    long deliveryTag,
                                    MqMessageIdentity identity,
                                    MqTaskLockLease lease,
                                    MqSessionExecLockLease sessionLease) throws IOException {
        T payload = null;
        // try-with-resources 确保无论成功/异常/return，最后都会 close watchdog registration，
        // close 内部会 future.cancel(false)，停止后续续租任务。
        try (LockWatchdog.Registration ignored =
                     (lease == null && sessionLease == null) ? (() -> {
                     }) : lockWatchdog.watch(lease, sessionLease)) {
            payload = deserializePayload(message);
            TaskReadiness readiness = checkReadiness(payload, identity);
            if (readiness.outcome() == TaskReadinessOutcome.WAIT) {
                // readiness WAIT 表示“当前不是不能处理，而是前置条件还没满足”，例如前一个 turn 未完成。
                // 释放 task lock 后延迟重投，让它稍后重新竞争。
                safeReleaseTaskLockForWait(identity, lease);
                requeueWithDelay(originalMessage(message, identity), channel, deliveryTag, identity, readiness.reason());
                return;
            }
            if (readiness.outcome() == TaskReadinessOutcome.SKIP) {
                // readiness SKIP 表示已经没必要处理，例如对应业务状态已完成。
                if (lease != null) {
                    distributedLockManager.markCompleted(lease);
                }
                channel.basicAck(deliveryTag, false);
                log.info("MQ task skipped after readiness check: taskType={}, eventId={}, reason={}",
                        identity.taskType(), identity.eventId(), readiness.reason());
                return;
            }
            // 容量门在 readiness PROCEED 后、processTask 前执行。
            // 钩子默认 no-op（知识库入库等不保护执行容量）；agent.run 子类覆写为真实许可获取。
            CapacityGateResult capacityResult = acquireCapacity(payload, identity);
            if (capacityResult instanceof CapacityGateResult.WaitInQueue) {
                // 容量不足：先检查是否已超 wait-timeout，否则节流发排队状态后延迟重投。
                // 两条分支都必须 return 经外层 finally，由 safeReleaseSessionExecLock 释放 session-exec，
                // 不能在 helper 内手动释放 session-exec（避免 double release）。
                MqMessageIdentity waitIdentity = handleCapacityWait(payload, identity);
                if (waitIdentity == TERMINAL_CAPACITY_WAIT) {
                    // 超时：按终态完成 turn 并 ACK。task lock 在钩子内标记 completed 后不再持有。
                    if (lease != null) {
                        runFailureHook(() -> distributedLockManager.markCompleted(lease), "capacity-wait-timeout-complete");
                    }
                    channel.basicAck(deliveryTag, false);
                    return;
                }
                // 未超时：释放 task lock 后用更新了容量等待字段的 identity 延迟重投，不增加 retryCount。
                // 必须用 MqMessageHeaders 重建完整 headers，而非 buildRetryMessage（只刷新 RETRY_COUNT，
                // 会丢失新的 capacityWait 字段——计划明确要求序列化完整 identity）。
                safeReleaseTaskLockForWait(identity, lease);
                requeueWithDelay(rebuildMessageWithIdentity(message, waitIdentity), channel, deliveryTag, waitIdentity, "execution capacity denied");
                return;
            }
            if (capacityResult instanceof CapacityGateResult.FailFast) {
                // Redis 不可用且策略为 FAIL_FAST：按终态失败处理。
                throw new CapacityUnavailableException("execution capacity unavailable (Redis FAIL_FAST)");
            }
            CapacityGateResult.Proceed proceed = (CapacityGateResult.Proceed) capacityResult;
            // 许可用 try-with-resources 包住 processTask，确保无论成功/异常都释放。
            try (Permit ignoredPermit = proceed.permit()) {
                // 真正的业务处理由子类实现：agent.run 会进 ChatEventProcessor，ingest 会跑文档入库。
                processTask(payload, identity);
                if (lease != null) {
                    distributedLockManager.markCompleted(lease);
                }
                channel.basicAck(deliveryTag, false);
                log.info("MQ task processed successfully: taskType={}, eventId={}, idempotencyKey={}",
                        identity.taskType(), identity.eventId(), identity.idempotencyKey());
            }
        } catch (Exception e) {
            // CapacityUnavailableException 是终态：Redis 故障 + FAIL_FAST 时重试也不会立即恢复。
            if (e instanceof CapacityUnavailableException) {
                handleTerminalFailure(payload, channel, deliveryTag, identity, lease, e);
            } else if (isRetryable(e)) {
                handleRetryableFailure(payload, message, channel, deliveryTag, identity, lease, sessionLease, e);
                return;
            } else {
                handleTerminalFailure(payload, channel, deliveryTag, identity, lease, e);
            }
        } finally {
            safeReleaseSessionExecLock(sessionLease);
        }
    }

    private void handleRetryableFailure(T payload,
                                        Message originalMessage,
                                        Channel channel,
                                        long deliveryTag,
                                        MqMessageIdentity identity,
                                        MqTaskLockLease lease,
                                        MqSessionExecLockLease sessionLease,
                                        Exception exception) throws IOException {
        if (identity.retryCount() >= maxRetryCount()) {
            // consumer retry 已耗尽：标记 Redis FAILED，然后 reject(false) 让 RabbitMQ 投到 DLQ。
            safeMarkFailed(lease, exception);
            runFailureHook(() -> onRetriesExhausted(payload, identity, exception), "retry-exhausted");
            log.warn("MQ task retries exhausted, sending to DLQ: taskType={}, eventId={}, retryCount={}",
                    identity.taskType(), identity.eventId(), identity.retryCount());
            channel.basicReject(deliveryTag, false);
            return;
        }

        if (lease != null) {
            boolean released;
            try {
                // 投递到 retry queue 前必须释放 RUNNING 锁，否则重试消息回来后会被自己挡住。
                released = distributedLockManager.releaseRunning(lease);
            } catch (Exception releaseException) {
                log.warn("Failed to release RUNNING lock before retry handoff, requeueing original: taskType={}, eventId={}",
                        identity.taskType(), identity.eventId(), releaseException);
                channel.basicNack(deliveryTag, false, true);
                return;
            }
            if (!released) {
                log.warn("RUNNING lock was not released before retry handoff, requeueing original: taskType={}, eventId={}",
                        identity.taskType(), identity.eventId());
                channel.basicNack(deliveryTag, false, true);
                return;
            }
        }
        MqMessageIdentity retryIdentity = identity.withRetryCount(identity.retryCount() + 1);
        try {
            // 显式发布到 retry exchange；retry queue TTL 到期后会死信回原主队列。
            rabbitMqMessagePublisher.publish(
                    retryExchange(),
                    retryRoutingKey(),
                    buildRetryMessage(originalMessage, retryIdentity),
                    retryCorrelationId(retryIdentity)
            );
            channel.basicAck(deliveryTag, false);
            log.warn("MQ task moved to retry queue: taskType={}, eventId={}, retryCount={}, error={}",
                    identity.taskType(),
                    identity.eventId(),
                    retryIdentity.retryCount(),
                    exception.getMessage());
        } catch (AmqpException mqException) {
            log.error("Failed to move MQ task to retry queue, requeueing original: taskType={}, eventId={}",
                    identity.taskType(), identity.eventId(), mqException);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void handleTerminalFailure(T payload,
                                       Channel channel,
                                       long deliveryTag,
                                       MqMessageIdentity identity,
                                       MqTaskLockLease lease,
                                       Exception exception) throws IOException {
        // 非重试异常直接进入终局失败：Redis 记 FAILED，RabbitMQ 进 DLQ。
        safeMarkFailed(lease, exception);
        runFailureHook(() -> onTerminalFailure(payload, identity, exception), "terminal-failure");
        log.warn("MQ task rejected as terminal failure: taskType={}, eventId={}, error={}",
                identity.taskType(), identity.eventId(), exception.getMessage());
        channel.basicReject(deliveryTag, false);
    }

    private void safeMarkFailed(MqTaskLockLease lease, Exception exception) {
        if (lease == null) {
            return;
        }
        try {
            distributedLockManager.markFailed(lease, abbreviateError(exception));
        } catch (Exception markFailedException) {
            log.warn("Failed to mark MQ task lock as FAILED: taskType={}, idempotencyKey={}, error={}",
                    lease.identity().taskType(),
                    lease.identity().idempotencyKey(),
                    markFailedException.getMessage());
        }
    }

    protected Message buildRetryMessage(Message originalMessage, MqMessageIdentity retryIdentity) {
        // 当前实现只更新 retry-count header，其余 identity 字段保持不变。
        return MessageBuilder.fromMessage(originalMessage)
                .setHeader(MqMessageHeaders.RETRY_COUNT, retryIdentity.retryCount())
                .build();
    }

    protected String retryCorrelationId(MqMessageIdentity retryIdentity) {
        return retryIdentity.eventId() + "-retry-" + retryIdentity.retryCount();
    }

    protected String consumerName() {
        return getClass().getSimpleName();
    }

    protected boolean requiresSessionExecutionLock(MqMessageIdentity identity) {
        // 有 sessionId 的任务才需要串行化；知识库 ingestion 这类无会话任务不拿 session lock。
        return StringUtils.hasText(identity.sessionId());
    }

    private void runFailureHook(FailureHook hook, String hookName) {
        try {
            hook.run();
        } catch (Exception hookException) {
            log.error("MQ consumer failure hook failed: consumer={}, hook={}, error={}",
                    consumerName(), hookName, hookException.getMessage(), hookException);
        }
    }

    private String abbreviateError(Exception e) {
        String message = e == null ? null : e.getMessage();
        if (message == null || message.isBlank()) {
            return e == null ? "Unknown consumer error" : e.getClass().getSimpleName();
        }
        return message.length() <= 800 ? message : message.substring(0, 800);
    }

    protected abstract String retryExchange();

    protected abstract String retryRoutingKey();

    protected abstract int maxRetryCount();

    protected abstract boolean isRetryable(Exception exception);

    protected abstract T deserializePayload(Message message) throws Exception;

    protected abstract void processTask(T payload, MqMessageIdentity identity) throws Exception;

    protected TaskReadiness checkReadiness(T payload, MqMessageIdentity identity) {
        return TaskReadiness.proceed();
    }

    /**
     * Execution-capacity gate hook. The default is a no-op so non-agent
     * consumers (e.g. knowledge ingest) are unaffected.
     *
     * <p>{@code AgentRunTaskListener} overrides this to acquire a Redis-backed
     * global permit before Agent execution.</p>
     *
     * @param payload  deserialized task payload
     * @param identity MQ message identity
     * @return gate result; default is {@link CapacityGateResult#proceed(Permit) proceed}
     *         with a {@link NoopPermit}
     */
    protected CapacityGateResult acquireCapacity(T payload, MqMessageIdentity identity) {
        return CapacityGateResult.proceed(NoopPermit.instance());
    }

    /**
     * Handles a capacity WAIT: checks the wait timeout, and if not exceeded,
     * emits a throttled queue-status notification and returns an updated
     * identity carrying the refreshed {@code capacityWaitStartedAt} /
     * {@code capacityWaitLastNotifiedAt} fields for requeue.
     *
     * <p>Base implementation performs no SSE emission (no-op status hook) and
     * uses a fixed {@link #capacityWaitTimeoutMs()} for the timeout check, so
     * non-agent consumers are unaffected. Returns the sentinel
     * {@link #TERMINAL_CAPACITY_WAIT} when the wait has timed out.</p>
     *
     * @param payload  deserialized task payload
     * @param identity current MQ message identity
     * @return updated identity for requeue, or {@link #TERMINAL_CAPACITY_WAIT} on timeout
     */
    protected MqMessageIdentity handleCapacityWait(T payload, MqMessageIdentity identity) {
        Instant now = clock.instant();
        Instant startedAt = identity.capacityWaitStartedAt() != null
                ? identity.capacityWaitStartedAt() : now;
        long timeoutMs = capacityWaitTimeoutMs();
        if (timeoutMs > 0 && now.minusMillis(timeoutMs).isAfter(startedAt)) {
            onCapacityWaitTimeout(payload, identity);
            return TERMINAL_CAPACITY_WAIT;
        }
        long intervalMs = capacityWaitStatusIntervalMs();
        Instant lastNotifiedAt = identity.capacityWaitLastNotifiedAt();
        Instant nextNotifiedAt = publishCapacityWaitStatus(payload, startedAt, lastNotifiedAt, now, intervalMs);
        return identity.withCapacityWait(startedAt, nextNotifiedAt);
    }

    /**
     * Capacity-wait timeout in milliseconds. {@code <= 0} disables the timeout
     * (waits indefinitely). Base default is 0; agent consumers override to bind
     * the wait.
     *
     * @return timeout in ms, or 0 to disable
     */
    protected long capacityWaitTimeoutMs() {
        return 0L;
    }

    /**
     * Minimum interval between queue-status notifications in milliseconds.
     *
     * @return interval in ms
     */
    protected long capacityWaitStatusIntervalMs() {
        return 0L;
    }

    /**
     * Emits a throttled queue-status notification during capacity WAIT.
     *
     * <p>Throttle rule: send immediately when {@code lastNotifiedAt} is absent
     * (first WAIT), otherwise send only after {@code intervalMs} has elapsed.
     * Returns the {@code Instant} to record as the new
     * {@code capacityWaitLastNotifiedAt} — {@code now} when a notification was
     * sent, or {@code lastNotifiedAt} unchanged when suppressed.</p>
     *
     * @param payload        deserialized task payload
     * @param startedAt      wait start instant
     * @param lastNotifiedAt last notification instant, or {@code null} on first WAIT
     * @param now            current instant
     * @param intervalMs     minimum interval between notifications
     * @return the instant to record as last-notified
     */
    protected Instant publishCapacityWaitStatus(T payload,
                                                Instant startedAt,
                                                Instant lastNotifiedAt,
                                                Instant now,
                                                long intervalMs) {
        // Base no-op: never sends SSE, preserves lastNotifiedAt as-is so the
        // identity is stable across requeues for non-agent consumers.
        return lastNotifiedAt;
    }

    /**
     * Terminal handler invoked when a capacity wait exceeds its timeout.
     *
     * <p>Base implementation is a no-op. Agent consumers override to publish a
     * user-visible failure (rollback, fallback assistant message, AI_ERROR /
     * AI_DONE) and complete the turn so later turns are not blocked by turn
     * sequence readiness.</p>
     *
     * @param payload  deserialized task payload
     * @param identity MQ message identity
     */
    protected void onCapacityWaitTimeout(T payload, MqMessageIdentity identity) {
    }

    protected void onRetriesExhausted(T payload, MqMessageIdentity identity, Exception exception) {
    }

    protected void onTerminalFailure(T payload, MqMessageIdentity identity, Exception exception) {
    }

    private SessionAcquireResult acquireSessionExecLockOrHandle(Message originalMessage,
                                                                Channel channel,
                                                                long deliveryTag,
                                                                MqMessageIdentity identity,
                                                                MqTaskLockLease taskLease) throws IOException {
        if (!requiresSessionExecutionLock(identity)) {
            return new SessionAcquireResult(false, null);
        }
        try {
            MqSessionExecLockAcquisition acquisition =
                    distributedLockManager.acquireSessionExecLock(identity.sessionId(), ownerId);
            if (acquisition.outcome() == MqTaskLockAcquireOutcome.WAIT_REQUIRED) {
                // 同一个 session 已有 Agent 正在跑。这里不能并发执行，否则消息落库/上下文读取会乱序。
                safeReleaseTaskLockForWait(identity, taskLease);
                requeueWithDelay(originalMessage, channel, deliveryTag, identity, "session execution lock is busy");
                return new SessionAcquireResult(true, null);
            }
            return new SessionAcquireResult(false, acquisition.lease());
        } catch (Exception acquisitionException) {
            ChatAgentMqProperties.RedisFailurePolicy policy =
                    properties.getLocks().getSessionExecPolicyForTask(identity.taskType());
            if (policy == ChatAgentMqProperties.RedisFailurePolicy.FAIL_OPEN) {
                log.warn("Redis failure during session-exec acquire, continuing without session serialization: taskType={}, eventId={}, sessionId={}, error={}",
                        identity.taskType(), identity.eventId(), identity.sessionId(), acquisitionException.getMessage());
                return new SessionAcquireResult(false, null);
            }
            safeReleaseTaskLockForWait(identity, taskLease);
            log.warn("Redis failure during session-exec acquire, requeueing because policy is FAIL_FAST: taskType={}, eventId={}, sessionId={}, error={}",
                    identity.taskType(), identity.eventId(), identity.sessionId(), acquisitionException.getMessage());
            channel.basicNack(deliveryTag, false, true);
            return new SessionAcquireResult(true, null);
        }
    }

    private void requeueWithDelay(Message originalMessage,
                                  Channel channel,
                                  long deliveryTag,
                                  MqMessageIdentity identity,
                                  String reason) throws IOException {
        try {
            // 延迟重投不是 basicNack(requeue=true) 立刻回队尾，而是进入 retry queue 等一段时间再回来。
            rabbitMqMessagePublisher.publish(
                    retryExchange(),
                    retryRoutingKey(),
                    originalMessage,
                    retryCorrelationId(identity) + "-wait-" + System.nanoTime()
            );
            channel.basicAck(deliveryTag, false);
            log.info("MQ task moved to delayed requeue: taskType={}, eventId={}, reason={}",
                    identity.taskType(), identity.eventId(), reason);
        } catch (AmqpException requeueException) {
            log.warn("Failed to move MQ task to delayed requeue, falling back to nack(requeue=true): taskType={}, eventId={}, reason={}",
                    identity.taskType(), identity.eventId(), reason, requeueException);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private Message originalMessage(Message message, MqMessageIdentity identity) {
        // WAIT 分支不是业务失败，所以 retryCount 不增加，只按当前 identity 原样重投。
        return buildRetryMessage(message, identity);
    }

    private Message rebuildMessageWithIdentity(Message message, MqMessageIdentity identity) {
        // 容量 WAIT 重投需要把更新后的 capacityWait* 字段写回 headers。
        // buildRetryMessage 只刷新 RETRY_COUNT，会保留旧的 capacity-wait header（或缺失），
        // 所以这里用 MqMessageHeaders.apply 完整重写 identity 相关 header，body 保持不变。
        return MessageBuilder.fromMessage(message)
                .andProperties(applyIdentityProperties(message, identity))
                .build();
    }

    private static org.springframework.amqp.core.MessageProperties applyIdentityProperties(
            Message message, MqMessageIdentity identity) {
        org.springframework.amqp.core.MessageProperties props = new org.springframework.amqp.core.MessageProperties();
        // 保留原 message 的 delivery tag 之外的非 identity header（content-type 等）。
        props.setContentType(message.getMessageProperties().getContentType());
        MqMessageHeaders.apply(props, identity);
        return props;
    }

    private void safeReleaseTaskLockForWait(MqMessageIdentity identity, MqTaskLockLease lease) {
        if (lease == null) {
            return;
        }
        try {
            // WAIT 不是完成也不是失败，必须释放 RUNNING 状态，否则后续重投永远拿不到锁。
            if (!distributedLockManager.releaseRunning(lease)) {
                log.warn("Failed to release task lock before wait requeue: taskType={}, eventId={}",
                        identity.taskType(), identity.eventId());
            }
        } catch (Exception e) {
            log.warn("Failed to release task lock before wait requeue: taskType={}, eventId={}, error={}",
                    identity.taskType(), identity.eventId(), e.getMessage());
        }
    }

    private void safeReleaseSessionExecLock(MqSessionExecLockLease lease) {
        if (lease == null) {
            return;
        }
        try {
            if (!distributedLockManager.releaseSessionExecLock(lease)) {
                log.warn("Failed to release session execution lock after processing: sessionId={}", lease.sessionId());
            }
        } catch (Exception e) {
            log.warn("Failed to release session execution lock after processing: sessionId={}, error={}",
                    lease.sessionId(),
                    e.getMessage());
        }
    }

    @FunctionalInterface
    private interface FailureHook {
        void run() throws Exception;
    }

    /**
     * Terminal exception thrown when execution capacity is unavailable and the
     * configured policy is {@code FAIL_FAST}. Handled as a terminal failure
     * (not retried) because Redis will not recover within the retry window.
     */
    public static final class CapacityUnavailableException extends RuntimeException {

        public CapacityUnavailableException(String message) {
            super(message);
        }
    }

    protected enum TaskReadinessOutcome {
        // 可以执行。
        PROCEED,
        // 暂时不能执行，延迟重投。
        WAIT,
        // 不需要执行，标记完成并 ack。
        SKIP
    }

    protected record TaskReadiness(TaskReadinessOutcome outcome, String reason) {
        static TaskReadiness proceed() {
            return new TaskReadiness(TaskReadinessOutcome.PROCEED, "ready");
        }

        static TaskReadiness waitRequired(String reason) {
            return new TaskReadiness(TaskReadinessOutcome.WAIT, reason);
        }

        static TaskReadiness skip(String reason) {
            return new TaskReadiness(TaskReadinessOutcome.SKIP, reason);
        }
    }

    private record SessionAcquireResult(boolean handled, MqSessionExecLockLease lease) {
    }
}
