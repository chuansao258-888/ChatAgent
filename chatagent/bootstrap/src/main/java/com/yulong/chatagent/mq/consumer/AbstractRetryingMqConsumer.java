package com.yulong.chatagent.mq.consumer;

import com.rabbitmq.client.Channel;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.lock.DistributedLockManager;
import com.yulong.chatagent.mq.lock.LockWatchdog;
import com.yulong.chatagent.mq.lock.MqTaskLockAcquireOutcome;
import com.yulong.chatagent.mq.lock.MqTaskLockAcquisition;
import com.yulong.chatagent.mq.lock.MqTaskLockLease;
import com.yulong.chatagent.mq.support.MqMessageHeaders;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.mq.support.RabbitMqMessagePublisher;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

import java.io.IOException;
import java.util.UUID;

/**
 * Shared retry, lock, and logging workflow for MQ task consumers.
 */
@Slf4j
public abstract class AbstractRetryingMqConsumer<T> {

    private final ChatAgentMqProperties properties;
    private final RabbitMqMessagePublisher rabbitMqMessagePublisher;
    private final DistributedLockManager distributedLockManager;
    private final LockWatchdog lockWatchdog;
    private final String ownerId;

    protected AbstractRetryingMqConsumer(ChatAgentMqProperties properties,
                                         RabbitMqMessagePublisher rabbitMqMessagePublisher,
                                         DistributedLockManager distributedLockManager,
                                         LockWatchdog lockWatchdog) {
        this.properties = properties;
        this.rabbitMqMessagePublisher = rabbitMqMessagePublisher;
        this.distributedLockManager = distributedLockManager;
        this.lockWatchdog = lockWatchdog;
        this.ownerId = consumerName() + ":" + UUID.randomUUID();
    }

    protected final void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        MqMessageIdentity identity = MqMessageHeaders.read(message.getMessageProperties());
        TraceContext.setTraceId(identity.traceId());
        try {
            MqTaskLockAcquisition acquisition;
            try {
                acquisition = distributedLockManager.tryAcquire(identity, ownerId);
            } catch (Exception acquisitionException) {
                ChatAgentMqProperties.RedisFailurePolicy policy =
                        properties.getLocks().getPolicyForTask(identity.taskType());
                if (policy == ChatAgentMqProperties.RedisFailurePolicy.FAIL_OPEN) {
                    log.warn("Redis failure during tryAcquire, continuing without idempotency: taskType={}, eventId={}, error={}",
                            identity.taskType(), identity.eventId(), acquisitionException.getMessage());
                    handleOwnedMessage(message, channel, deliveryTag, identity, null);
                    return;
                }
                log.warn("Redis failure during tryAcquire, requeueing because policy is FAIL_FAST: taskType={}, eventId={}, error={}",
                        identity.taskType(), identity.eventId(), acquisitionException.getMessage());
                channel.basicNack(deliveryTag, false, true);
                return;
            }
            if (acquisition.outcome() == MqTaskLockAcquireOutcome.WAIT_REQUIRED) {
                log.info("MQ task is currently RUNNING by another instance, requeueing to wait: taskType={}, eventId={}",
                        identity.taskType(), identity.eventId());
                // Mitigate hot-looping by introducing a small passive delay before nack(requeue=true)
                Thread.sleep(200);
                channel.basicNack(deliveryTag, false, true);
                return;
            }
            if (acquisition.outcome() == MqTaskLockAcquireOutcome.DUPLICATE) {
                log.info("MQ task skipped as duplicate: taskType={}, eventId={}, state={}",
                        identity.taskType(), identity.eventId(), acquisition.existingState());
                channel.basicAck(deliveryTag, false);
                return;
            }
            handleOwnedMessage(message, channel, deliveryTag, identity, acquisition.lease());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Consumer interrupted during wait/requeue: taskType={}, eventId={}",
                    identity.taskType(), identity.eventId());
            channel.basicNack(deliveryTag, false, true);
        } finally {
            TraceContext.clear();
        }
    }

    private void handleOwnedMessage(Message message,
                                    Channel channel,
                                    long deliveryTag,
                                    MqMessageIdentity identity,
                                    MqTaskLockLease lease) throws IOException {
        T payload = null;
        try (LockWatchdog.Registration ignored = lease == null ? () -> { } : lockWatchdog.watch(lease)) {
            payload = deserializePayload(message);
            processTask(payload, identity);
            if (lease != null) {
                distributedLockManager.markCompleted(lease);
            }
            channel.basicAck(deliveryTag, false);
            log.info("MQ task processed successfully: taskType={}, eventId={}, idempotencyKey={}",
                    identity.taskType(), identity.eventId(), identity.idempotencyKey());
        } catch (Exception e) {
            if (isRetryable(e)) {
                handleRetryableFailure(payload, message, channel, deliveryTag, identity, lease, e);
                return;
            }
            handleTerminalFailure(payload, channel, deliveryTag, identity, lease, e);
        }
    }

    private void handleRetryableFailure(T payload,
                                        Message originalMessage,
                                        Channel channel,
                                        long deliveryTag,
                                        MqMessageIdentity identity,
                                        MqTaskLockLease lease,
                                        Exception exception) throws IOException {
        if (identity.retryCount() >= maxRetryCount()) {
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

    protected void onRetriesExhausted(T payload, MqMessageIdentity identity, Exception exception) {
    }

    protected void onTerminalFailure(T payload, MqMessageIdentity identity, Exception exception) {
    }

    @FunctionalInterface
    private interface FailureHook {
        void run() throws Exception;
    }
}
