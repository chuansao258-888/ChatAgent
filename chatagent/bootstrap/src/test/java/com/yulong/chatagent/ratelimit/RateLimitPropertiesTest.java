package com.yulong.chatagent.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitPropertiesTest {

    @Test
    void shouldBindDocumentedDefaults() {
        RateLimitProperties properties = new RateLimitProperties();

        assertThat(properties.getEntry().isEnabled()).isTrue();
        assertThat(properties.getEntry().getRequestsPerSecond()).isEqualTo(2);
        assertThat(properties.getEntry().getBurstCapacity()).isEqualTo(5);
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
}
