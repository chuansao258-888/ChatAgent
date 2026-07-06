package com.yulong.chatagent.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the two ChatAgent rate-limiting layers.
 *
 * <p>The {@link Entry} layer guards {@code POST /api/chat-messages} with a
 * token bucket keyed by authenticated user (IP fallback). The {@link AgentRun}
 * layer guards Agent execution with a Redis-backed global permit set.</p>
 *
 * <p>中文说明：两层限流配置。Entry 保护 HTTP 入口，AgentRun 保护
 * agent.run 的全局并发执行上限。</p>
 */
@Component
@ConfigurationProperties(prefix = "chatagent.rate-limit")
@Data
public class RateLimitProperties {

    /**
     * Entry-layer token-bucket limiter for chat message creation.
     */
    private Entry entry = new Entry();

    /**
     * Execution-layer global permit gate for agent.run tasks.
     */
    private AgentRun agentRun = new AgentRun();

    @Data
    public static class Entry {

        /**
         * Master switch. When {@code false}, the entry limiter is bypassed and
         * current HTTP entry behavior is preserved.
         */
        private boolean enabled = true;

        /**
         * Steady-state allowed requests per second per identity (user or IP).
         */
        private int requestsPerSecond = 2;

        /**
         * Maximum tokens that may accumulate for a single identity, allowing
         * short bursts up to this size.
         */
        private int burstCapacity = 5;

        /**
         * Behavior when Redis is unavailable. Defaults to {@code LOCAL_BUCKET}
         * so a transient Redis outage does not disable chat entry.
         */
        private RateLimitFailurePolicy redisFailurePolicy = RateLimitFailurePolicy.LOCAL_BUCKET;
    }

    @Data
    public static class AgentRun {

        /**
         * Master switch. When {@code false}, the execution capacity gate is
         * bypassed and current MQ execution behavior is preserved.
         */
        private boolean enabled = true;

        /**
         * Global maximum number of concurrently executing Agent runs enforced
         * across all backend instances via Redis.
         */
        private int maxConcurrency = 3;

        /**
         * Time-to-live in milliseconds for an execution permit before it is
         * considered expired. Renewed by the permit watchdog during long runs.
         */
        private long permitTtlMs = 180000L;

        /**
         * Interval in milliseconds at which the permit watchdog refreshes the
         * permit score before {@link #permitTtlMs} elapses.
         */
        private long permitRenewIntervalMs = 20000L;

        /**
         * Maximum time in milliseconds a turn may wait for execution capacity
         * via MQ delayed requeue before a user-visible failure is published.
         */
        private long waitTimeoutMs = 120000L;

        /**
         * Minimum interval in milliseconds between queue/wait status SSE events
         * for the same turn, throttling duplicate status notifications.
         */
        private long waitStatusIntervalMs = 5000L;

        /**
         * Behavior when Redis is unavailable. Defaults to {@code LOCAL_CAP}
         * so a transient Redis outage permits a bounded number of runs.
         */
        private RateLimitFailurePolicy redisFailurePolicy = RateLimitFailurePolicy.LOCAL_CAP;

        /**
         * Maximum number of Agent runs allowed per JVM when Redis is
         * unavailable and the policy is {@code LOCAL_CAP}.
         */
        private int localCapacityOnRedisFailure = 1;
    }
}
