package com.yulong.chatagent.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesTest {

    @Test
    void shouldBindDocumentedDefaults() {
        RateLimitProperties properties = new RateLimitProperties();

        assertThat(properties.getEntry().isEnabled()).isTrue();
        assertThat(properties.getEntry().getRequestsPerSecond()).isEqualTo(2);
        assertThat(properties.getEntry().getBurstCapacity()).isEqualTo(5);
        assertThat(properties.getEntry().getLocalFallbackMaxIdentities()).isEqualTo(10_000);
        assertThat(properties.getEntry().getRedisFailurePolicy()).isEqualTo(RateLimitFailurePolicy.LOCAL_BUCKET);

        assertThat(properties.getAgentRun().isEnabled()).isTrue();
        assertThat(properties.getAgentRun().getMaxConcurrency()).isEqualTo(3);
        assertThat(properties.getAgentRun().getPermitTtlMs()).isEqualTo(180000L);
        assertThat(properties.getAgentRun().getPermitRenewIntervalMs()).isEqualTo(20000L);
        assertThat(properties.getAgentRun().getWaitTimeoutMs()).isEqualTo(120000L);
        assertThat(properties.getAgentRun().getWaitStatusIntervalMs()).isEqualTo(5000L);
        assertThat(properties.getAgentRun().getRedisFailurePolicy()).isEqualTo(RateLimitFailurePolicy.LOCAL_CAP);
        assertThat(properties.getAgentRun().getLocalCapacityOnRedisFailure()).isEqualTo(1);
    }

    @Test
    void shouldAllowIndependentFeatureFlagging() {
        RateLimitProperties properties = new RateLimitProperties();

        properties.getEntry().setEnabled(false);
        properties.getAgentRun().setEnabled(false);

        // Each layer must be flaggable without affecting the other.
        assertThat(properties.getEntry().isEnabled()).isFalse();
        assertThat(properties.getAgentRun().isEnabled()).isFalse();

        properties.getEntry().setEnabled(true);
        assertThat(properties.getEntry().isEnabled()).isTrue();
        assertThat(properties.getAgentRun().isEnabled()).isFalse();
    }

    @Test
    void shouldKeepEntryAndExecutionPoliciesDistinct() {
        RateLimitProperties properties = new RateLimitProperties();

        // Entry uses LOCAL_BUCKET; execution uses LOCAL_CAP by default. They must not drift.
        assertThat(properties.getEntry().getRedisFailurePolicy()).isNotEqualTo(
                properties.getAgentRun().getRedisFailurePolicy());
    }

    @Test
    void shouldRejectInvalidEntryLimits() {
        RateLimitProperties properties = new RateLimitProperties();

        properties.getEntry().setRequestsPerSecond(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entry.requests-per-second");

        properties.getEntry().setRequestsPerSecond(1);
        properties.getEntry().setBurstCapacity(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entry.burst-capacity");

        properties.getEntry().setBurstCapacity(1);
        properties.getEntry().setLocalFallbackMaxIdentities(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entry.local-fallback-max-identities");
    }

    @Test
    void shouldRejectInvalidAgentRunTimingsAndLimits() {
        RateLimitProperties properties = new RateLimitProperties();

        properties.getAgentRun().setMaxConcurrency(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent-run.max-concurrency");

        properties.getAgentRun().setMaxConcurrency(1);
        properties.getAgentRun().setPermitTtlMs(0L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent-run.permit-ttl-ms");

        properties.getAgentRun().setPermitTtlMs(100L);
        properties.getAgentRun().setPermitRenewIntervalMs(100L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be less than permit-ttl-ms");

        properties.getAgentRun().setPermitRenewIntervalMs(10L);
        properties.getAgentRun().setWaitTimeoutMs(-1L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent-run.wait-timeout-ms");

        properties.getAgentRun().setWaitTimeoutMs(0L);
        properties.getAgentRun().setWaitStatusIntervalMs(-1L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent-run.wait-status-interval-ms");

        properties.getAgentRun().setWaitStatusIntervalMs(0L);
        properties.getAgentRun().setLocalCapacityOnRedisFailure(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent-run.local-capacity-on-redis-failure");
    }
}
