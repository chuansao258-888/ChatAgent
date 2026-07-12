package com.yulong.chatagent.ratelimit.capacity;

import com.yulong.chatagent.ratelimit.RateLimitFailurePolicy;
import com.yulong.chatagent.ratelimit.RateLimitMetricsRecorder;
import com.yulong.chatagent.ratelimit.RateLimitProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis-backed global execution permit gate for {@code agent.run} tasks.
 *
 * <p>Uses a Redis sorted set ({@code chatagent:agent-run:active}) scored by
 * permit expiry epoch-millis. An atomic Lua script cleans expired permits,
 * checks the active count against {@code max-concurrency}, and admits only
 * when below the limit. A scheduled watchdog refreshes the score before
 * {@code permit-ttl-ms} elapses so long DeepThink runs remain counted.</p>
 *
 * <p>On Redis failure, the configured {@link RateLimitFailurePolicy} applies:
 * {@code LOCAL_CAP} (default) acquires a bounded JVM {@link Semaphore};
 * {@code FAIL_FAST} rejects immediately. LOCAL_CAP permits hold the semaphore
 * to completion and do not attempt Redis renewal.</p>
 *
 * <p>中文说明：agent.run 全局执行许可门。Redis ZSET + Lua 原子获取/释放，
 * watchdog 续租保证长任务仍被计数；Redis 故障时 LOCAL_CAP 用本地信号量兜底。</p>
 */
@Slf4j
@Component
public class AgentRunCapacityLimiter {

    static final String ACTIVE_PERMITS_KEY = "chatagent:agent-run:active";

    static final DefaultRedisScript<Long> ACQUIRE_SCRIPT;
    static final DefaultRedisScript<Long> RENEW_SCRIPT;
    static final DefaultRedisScript<Long> RELEASE_SCRIPT;
    static {
        ACQUIRE_SCRIPT = new DefaultRedisScript<>();
        ACQUIRE_SCRIPT.setScriptText("""
                local key = KEYS[1]
                local maxConcurrency = tonumber(ARGV[1])
                local ttlMs = tonumber(ARGV[2])
                local permitId = ARGV[3]
                local serverTime = redis.call('TIME')
                local now = (tonumber(serverTime[1]) * 1000) + math.floor(tonumber(serverTime[2]) / 1000)
                local permitExpireAt = now + ttlMs

                redis.call('zremrangebyscore', key, '-inf', now)
                local active = redis.call('zcard', key)
                if active >= maxConcurrency then
                    return 0
                end
                redis.call('zadd', key, permitExpireAt, permitId)
                return 1
                """);
        ACQUIRE_SCRIPT.setResultType(Long.class);

        RENEW_SCRIPT = new DefaultRedisScript<>();
        RENEW_SCRIPT.setScriptText("""
                local key = KEYS[1]
                local ttlMs = tonumber(ARGV[1])
                local permitId = ARGV[2]
                local serverTime = redis.call('TIME')
                local now = (tonumber(serverTime[1]) * 1000) + math.floor(tonumber(serverTime[2]) / 1000)
                local currentExpireAt = redis.call('zscore', key, permitId)

                if currentExpireAt == false then
                    return 0
                end
                if tonumber(currentExpireAt) <= now then
                    redis.call('zrem', key, permitId)
                    return 0
                end
                redis.call('zadd', key, 'XX', now + ttlMs, permitId)
                return 1
                """);
        RENEW_SCRIPT.setResultType(Long.class);

        RELEASE_SCRIPT = new DefaultRedisScript<>();
        RELEASE_SCRIPT.setScriptText("""
                local key = KEYS[1]
                local permitId = ARGV[1]
                return redis.call('zrem', key, permitId)
                """);
        RELEASE_SCRIPT.setResultType(Long.class);
    }

    private final RateLimitProperties properties;
    private final RateLimitMetricsRecorder metricsRecorder;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ScheduledExecutorService scheduler;
    private final Semaphore localCapSemaphore;

    @Autowired
    public AgentRunCapacityLimiter(RateLimitProperties properties,
                                   RateLimitMetricsRecorder metricsRecorder,
                                   ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(properties, metricsRecorder, redisTemplateProvider,
                Executors.newScheduledThreadPool(1, new PermitWatchdogThreadFactory()),
                new Semaphore(properties.getAgentRun().getLocalCapacityOnRedisFailure(), true));
    }

    AgentRunCapacityLimiter(RateLimitProperties properties,
                            RateLimitMetricsRecorder metricsRecorder,
                            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                            ScheduledExecutorService scheduler,
                            Semaphore localCapSemaphore) {
        this.properties = properties;
        this.metricsRecorder = metricsRecorder;
        this.redisTemplateProvider = redisTemplateProvider;
        this.scheduler = scheduler;
        this.localCapSemaphore = localCapSemaphore;
    }

    /**
     * Local-only capacity acquire used when MQ is disabled (dev path).
     *
     * <p>Acquires the bounded JVM {@link Semaphore} without touching Redis,
     * matching the production Redis-down LOCAL_CAP degradation. The permit
     * holds the semaphore to completion and performs no Redis renewal.</p>
     *
     * @return gate result: {@link CapacityGateResult.Proceed} with a local permit,
     *         or {@link CapacityGateResult.WaitInQueue} if the local cap is exhausted
     */
    public CapacityGateResult tryAcquireLocalCapOnly() {
        if (!properties.getAgentRun().isEnabled()) {
            return CapacityGateResult.proceed(NoopPermit.instance());
        }
        boolean acquired = localCapSemaphore.tryAcquire();
        if (acquired) {
            metricsRecorder.recordCapacityAcquire("fallback", "local_cap");
            return CapacityGateResult.proceed(new LocalCapPermit(localCapSemaphore, metricsRecorder));
        }
        metricsRecorder.recordCapacityAcquire("denied", "local_cap");
        return CapacityGateResult.waitInQueue();
    }

    /**
     * Attempts to acquire an execution permit for an Agent run.
     *
     * @param context permit context (used for logging/member identity)
     * @return gate result: {@link CapacityGateResult.Proceed} with a permit to release,
     *         {@link CapacityGateResult.WaitInQueue} if no capacity,
     *         {@link CapacityGateResult.FailFast} if Redis is down and policy is FAIL_FAST
     */
    public CapacityGateResult tryAcquire(PermitContext context) {
        if (!properties.getAgentRun().isEnabled()) {
            return CapacityGateResult.proceed(NoopPermit.instance());
        }
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return handleRedisUnavailable();
        }
        try {
            String permitId = context.member();
            Long granted = redisTemplate.execute(
                    ACQUIRE_SCRIPT,
                    List.of(ACTIVE_PERMITS_KEY),
                    String.valueOf(properties.getAgentRun().getMaxConcurrency()),
                    String.valueOf(properties.getAgentRun().getPermitTtlMs()),
                    permitId
            );
            if (granted != null && granted == 1L) {
                try {
                    Permit permit = startRenewal(permitId, redisTemplate);
                    metricsRecorder.recordCapacityAcquire("allowed", "redis");
                    return CapacityGateResult.proceed(permit);
                } catch (RuntimeException schedulerFailure) {
                    log.error("Permit watchdog could not start; compensating Redis grant: member={}, error={}",
                            permitId, schedulerFailure.getMessage());
                    metricsRecorder.recordCapacityAcquire("failed", "watchdog");
                    compensateGrantedPermit(permitId, redisTemplate);
                    return handleRedisUnavailable();
                }
            }
            if (granted != null) {
                metricsRecorder.recordCapacityAcquire("denied", "redis");
                return CapacityGateResult.waitInQueue();
            }
            return handleRedisUnavailable();
        } catch (Exception e) {
            log.warn("Capacity acquire Redis call failed, applying fallback policy: member={}, error={}",
                    context.member(), e.getMessage());
            metricsRecorder.recordCapacityRedisFailure();
            return handleRedisUnavailable();
        }
    }

    private CapacityGateResult handleRedisUnavailable() {
        RateLimitFailurePolicy policy = properties.getAgentRun().getRedisFailurePolicy();
        if (policy == RateLimitFailurePolicy.FAIL_FAST) {
            metricsRecorder.recordCapacityAcquire("denied", "fail_fast");
            return CapacityGateResult.failFast();
        }
        // LOCAL_CAP: bounded local semaphore. Permits finish locally after Redis recovers.
        boolean acquired = localCapSemaphore.tryAcquire();
        if (acquired) {
            metricsRecorder.recordCapacityAcquire("fallback", "local_cap");
            return CapacityGateResult.proceed(new LocalCapPermit(localCapSemaphore, metricsRecorder));
        }
        metricsRecorder.recordCapacityAcquire("denied", "local_cap");
        return CapacityGateResult.waitInQueue();
    }

    private Permit startRenewal(String permitId, StringRedisTemplate redisTemplate) {
        long intervalMs = properties.getAgentRun().getPermitRenewIntervalMs();
        long ttlMs = properties.getAgentRun().getPermitTtlMs();
        RedisPermit permit = new RedisPermit(
                permitId, redisTemplate, ttlMs, metricsRecorder, System.nanoTime());
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                permit::renew,
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
        permit.attachRenewalFuture(future);
        return permit;
    }

    private void compensateGrantedPermit(String permitId, StringRedisTemplate redisTemplate) {
        try {
            Long removed = redisTemplate.execute(
                    RELEASE_SCRIPT, List.of(ACTIVE_PERMITS_KEY), permitId);
            if (removed == null) {
                metricsRecorder.recordCapacityRedisFailure();
                metricsRecorder.recordPermitRelease("failed");
            } else {
                metricsRecorder.recordPermitRelease(removed == 1L ? "removed" : "absent");
            }
        } catch (Exception releaseFailure) {
            log.warn("Compensating permit release failed; Redis TTL remains authoritative: member={}, error={}",
                    permitId, releaseFailure.getMessage());
            metricsRecorder.recordCapacityRedisFailure();
            metricsRecorder.recordPermitRelease("failed");
        }
    }

    /**
     * Records that a capacity wait timed out, including how long the turn waited.
     *
     * @param startedAt the instant the capacity wait began (from MqMessageIdentity)
     */
    public void recordWaitTimeout(java.time.Instant startedAt) {
        metricsRecorder.recordCapacityWait("timeout");
        if (startedAt != null) {
            long waitedMs = Math.max(0L, java.time.Duration.between(startedAt, java.time.Instant.now()).toMillis());
            metricsRecorder.recordCapacityWaitDuration(waitedMs);
        }
    }

    /**
     * Records that a capacity WAIT was handed off to the delayed MQ requeue.
     */
    public void recordWaitRequeued() {
        metricsRecorder.recordCapacityWait("requeued");
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * Context identifying a permit holder.
     *
     * @param member ZSET member string, unique per acquired permit
     */
    public record PermitContext(String member) {

        /**
         * Builds a unique permit context for the given task identity.
         *
         * @param ownerId consumer owner id
         * @param eventId MQ event id
         * @param turnId turn id
         * @return unique permit context
         */
        public static PermitContext forTask(String ownerId, String eventId, String turnId) {
            return new PermitContext(ownerId + ":" + eventId + ":" + turnId + ":" + UUID.randomUUID());
        }
    }

    /** Redis-backed permit: removes its member from the ZSET and cancels renewal. */
    private static final class RedisPermit implements Permit {

        private final String permitId;
        private final StringRedisTemplate redisTemplate;
        private final long ttlMs;
        private final RateLimitMetricsRecorder metricsRecorder;
        private final long acquiredAtNanos;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean renewalActive = new AtomicBoolean(true);
        private volatile ScheduledFuture<?> renewalFuture;

        private RedisPermit(String permitId,
                            StringRedisTemplate redisTemplate,
                            long ttlMs,
                            RateLimitMetricsRecorder metricsRecorder,
                            long acquiredAtNanos) {
            this.permitId = permitId;
            this.redisTemplate = redisTemplate;
            this.ttlMs = ttlMs;
            this.metricsRecorder = metricsRecorder;
            this.acquiredAtNanos = acquiredAtNanos;
        }

        private void attachRenewalFuture(ScheduledFuture<?> future) {
            this.renewalFuture = future;
        }

        private void renew() {
            if (closed.get() || !renewalActive.get()) {
                return;
            }
            try {
                Long renewed = redisTemplate.execute(
                        RENEW_SCRIPT,
                        List.of(ACTIVE_PERMITS_KEY),
                        String.valueOf(ttlMs),
                        permitId
                );
                if (renewed != null && renewed == 1L) {
                    metricsRecorder.recordPermitRenewal("renewed");
                    return;
                }
                if (renewed != null) {
                    metricsRecorder.recordPermitRenewal("lost");
                    stopRenewal();
                    log.warn("Permit lease lost; Agent run continues without resurrecting membership: member={}",
                            permitId);
                    return;
                }
                metricsRecorder.recordPermitRenewal("failed");
                metricsRecorder.recordCapacityRedisFailure();
            } catch (Exception e) {
                log.warn("Permit renewal failed, run continues but may be over-admitted: member={}, error={}",
                        permitId, e.getMessage());
                metricsRecorder.recordPermitRenewal("failed");
                metricsRecorder.recordCapacityRedisFailure();
            }
        }

        private void stopRenewal() {
            if (!renewalActive.compareAndSet(true, false)) {
                return;
            }
            ScheduledFuture<?> future = renewalFuture;
            if (future != null) {
                future.cancel(false);
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            stopRenewal();
            try {
                Long removed = redisTemplate.execute(
                        RELEASE_SCRIPT, List.of(ACTIVE_PERMITS_KEY), permitId);
                if (removed == null) {
                    metricsRecorder.recordCapacityRedisFailure();
                    metricsRecorder.recordPermitRelease("failed");
                } else {
                    metricsRecorder.recordPermitRelease(removed == 1L ? "removed" : "absent");
                }
            } catch (Exception e) {
                // Release failure must not mask the original Agent result.
                log.warn("Permit release failed, leaving it to expire via TTL: member={}, ttlMs={}, error={}",
                        permitId, ttlMs, e.getMessage());
                metricsRecorder.recordCapacityRedisFailure();
                metricsRecorder.recordPermitRelease("failed");
            }
            long heldMs = Math.max(0L, (System.nanoTime() - acquiredAtNanos) / 1_000_000L);
            metricsRecorder.recordPermitHoldDuration(heldMs);
        }
    }

    /** Local semaphore permit: releases one permit on close. No Redis interaction. */
    private static final class LocalCapPermit implements Permit {

        private final Semaphore semaphore;
        private final RateLimitMetricsRecorder metricsRecorder;
        private final long acquiredAtNanos;

        private LocalCapPermit(Semaphore semaphore, RateLimitMetricsRecorder metricsRecorder) {
            this.semaphore = semaphore;
            this.metricsRecorder = metricsRecorder;
            this.acquiredAtNanos = System.nanoTime();
        }

        @Override
        public void close() {
            semaphore.release();
            long heldMs = Math.max(0L, (System.nanoTime() - acquiredAtNanos) / 1_000_000L);
            metricsRecorder.recordPermitHoldDuration(heldMs);
        }
    }

    private static final class PermitWatchdogThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "agent-permit-watchdog-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
