package com.yulong.chatagent.mcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerRateLimiterTest {

    @Test
    void shouldRefillTokensPerServerOverTime() {
        McpRuntimeProtectionProperties properties = new McpRuntimeProtectionProperties();
        properties.setRateLimitRequestsPerSecond(1);
        properties.setRateLimitBurstCapacity(1);
        AtomicLong now = new AtomicLong(0L);
        McpServerRateLimiter rateLimiter = new McpServerRateLimiter(properties, now::get);

        assertThat(rateLimiter.tryAcquire("srv-1")).isTrue();
        assertThat(rateLimiter.tryAcquire("srv-1")).isFalse();

        now.set(1_000_000_000L);
        assertThat(rateLimiter.tryAcquire("srv-1")).isTrue();
        assertThat(rateLimiter.tryAcquire("srv-2")).isTrue();
    }
}
