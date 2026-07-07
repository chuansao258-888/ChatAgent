package com.yulong.chatagent.ratelimit;

/**
 * Rate-limit-specific Redis failure policies, intentionally separate from
 * {@code ChatAgentMqProperties.RedisFailurePolicy} so MQ lock behavior and
 * limiter degradation cannot drift together by accident.
 *
 * <p>中文说明：限流器专用的 Redis 失败策略，与 MQ 锁的策略分离，
 * 避免某次改动同时波及 MQ 幂等链路和限流降级。</p>
 */
public enum RateLimitFailurePolicy {

    /**
     * Entry layer only: fall back to a JVM-local token bucket so transient
     * Redis outages do not disable chat entry. Never used by the execution layer.
     */
    LOCAL_BUCKET,

    /**
     * Execution layer default: allow at most {@code local-capacity-on-redis-failure}
     * Agent runs per JVM while Redis is unavailable. Bounded degradation.
     */
    LOCAL_CAP,

    /**
     * Execution layer strict mode: reject immediately when Redis is unavailable,
     * providing stronger provider-cost protection at the cost of availability.
     */
    FAIL_FAST
}
