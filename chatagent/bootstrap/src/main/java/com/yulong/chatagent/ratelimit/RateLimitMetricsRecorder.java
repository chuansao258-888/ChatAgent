package com.yulong.chatagent.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Centralized Micrometer recorder for rate-limiter signals.
 *
 * <p>Follows the {@code ObjectProvider<MeterRegistry>} + null-safe pattern used
 * by {@code McpMetricsRecorder} so the limiter remains usable in tests without
 * a meter registry. Tag values are low-cardinality and sanitized so no raw IP
 * address, prompt, or rate-limit key is emitted.</p>
 *
 * <p>中文说明：限流器指标记录器。所有 tag 均为低基数枚举值，
 * 不包含原始 IP、prompt 或限流键。</p>
 */
@Component
public class RateLimitMetricsRecorder {

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry meterRegistry;

    public RateLimitMetricsRecorder(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /**
     * Records an entry-layer request outcome.
     *
     * @param outcome {@code allowed}, {@code rejected}, or {@code fallback}
     * @param scope   {@code user} or {@code ip}
     * @param policy  {@code redis} or {@code local_bucket}
     */
    public void recordEntryRequest(String outcome, String scope, String policy) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("chatagent.rate_limit.entry.requests",
                "outcome", normalize(outcome),
                "scope", normalize(scope),
                "policy", normalize(policy)
        ).increment();
    }

    /**
     * Records an execution-layer capacity acquire outcome.
     *
     * @param outcome {@code allowed}, {@code denied}, or {@code fallback}
     * @param policy  {@code redis}, {@code local_cap}, or {@code fail_fast}
     */
    public void recordCapacityAcquire(String outcome, String policy) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("chatagent.agent_run.capacity.acquire",
                "outcome", normalize(outcome),
                "policy", normalize(policy)
        ).increment();
    }

    /**
     * Records a capacity wait outcome (e.g. requeued, timeout).
     *
     * @param outcome wait outcome label
     */
    public void recordCapacityWait(String outcome) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("chatagent.agent_run.capacity.waits",
                "outcome", normalize(outcome)
        ).increment();
    }

    /**
     * Records the duration spent waiting for execution capacity.
     *
     * @param durationMs milliseconds spent waiting
     */
    public void recordCapacityWaitDuration(long durationMs) {
        if (meterRegistry == null) {
            return;
        }
        Timer.builder("chatagent.agent_run.capacity.wait.duration")
                .register(meterRegistry)
                .record(Duration.ofMillis(Math.max(0L, durationMs)));
    }

    /**
     * Records the duration an execution permit was held.
     *
     * @param durationMs milliseconds the permit was held
     */
    public void recordPermitHoldDuration(long durationMs) {
        if (meterRegistry == null) {
            return;
        }
        Timer.builder("chatagent.agent_run.capacity.permit.hold.duration")
                .register(meterRegistry)
                .record(Duration.ofMillis(Math.max(0L, durationMs)));
    }

    /**
     * Records a Redis failure encountered by the execution capacity limiter.
     */
    public void recordCapacityRedisFailure() {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("chatagent.agent_run.capacity.redis.failures").increment();
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : UNKNOWN;
    }
}
