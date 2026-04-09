package com.yulong.chatagent.mcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerCircuitBreakerTest {

    @Test
    void shouldOpenAfterFailureRateThresholdAndRecoverAfterHalfOpenProbe() {
        McpRuntimeProtectionProperties properties = new McpRuntimeProtectionProperties();
        properties.setCircuitBreakerSlidingWindowSize(3);
        properties.setCircuitBreakerMinimumRequestVolume(3);
        properties.setCircuitBreakerFailureThreshold(2);
        properties.setCircuitBreakerFailureRateThresholdPercent(50);
        properties.setCircuitBreakerHalfOpenProbeCount(1);
        properties.setCircuitBreakerOpenStateMs(1000L);
        AtomicLong now = new AtomicLong(0L);
        McpServerCircuitBreaker circuitBreaker = new McpServerCircuitBreaker(properties, now::get);

        assertThat(circuitBreaker.allowRequest()).isTrue();
        circuitBreaker.recordFailure(100);
        assertThat(circuitBreaker.allowRequest()).isTrue();
        circuitBreaker.recordFailure(100);
        assertThat(circuitBreaker.allowRequest()).isTrue();
        circuitBreaker.recordSuccess(100);

        assertThat(circuitBreaker.getState()).isEqualTo(McpServerCircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.allowRequest()).isFalse();

        now.set(1001L);
        assertThat(circuitBreaker.allowRequest()).isTrue();
        assertThat(circuitBreaker.getState()).isEqualTo(McpServerCircuitBreaker.State.HALF_OPEN);

        circuitBreaker.recordSuccess(100);
        assertThat(circuitBreaker.getState()).isEqualTo(McpServerCircuitBreaker.State.CLOSED);
    }

    @Test
    void shouldOpenWhenSlowCallRateCrossesThreshold() {
        McpRuntimeProtectionProperties properties = new McpRuntimeProtectionProperties();
        properties.setCircuitBreakerSlidingWindowSize(2);
        properties.setCircuitBreakerMinimumRequestVolume(2);
        properties.setCircuitBreakerFailureThreshold(2);
        properties.setCircuitBreakerFailureRateThresholdPercent(100);
        properties.setCircuitBreakerSlowCallDurationMs(50L);
        properties.setCircuitBreakerSlowCallRateThresholdPercent(80);
        McpServerCircuitBreaker circuitBreaker = new McpServerCircuitBreaker(properties, () -> 0L);

        assertThat(circuitBreaker.allowRequest()).isTrue();
        circuitBreaker.recordSuccess(100);
        assertThat(circuitBreaker.allowRequest()).isTrue();
        circuitBreaker.recordSuccess(100);

        assertThat(circuitBreaker.getState()).isEqualTo(McpServerCircuitBreaker.State.OPEN);
    }
}
