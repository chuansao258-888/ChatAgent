package com.yulong.chatagent.rag.parser;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

class VdpMetricsSupportTest {

    @Test
    void shouldAggregatePageStatusesByFailureThenDegradedThenSuccess() {
        assertThat(VdpMetricsSupport.aggregateStatus(List.of(
                new VdpPageResult(0, "", VdpPageStatus.SUCCESS, Map.of()),
                new VdpPageResult(1, "", VdpPageStatus.DEGRADED, Map.of())
        ), null)).isEqualTo("DEGRADED");

        assertThat(VdpMetricsSupport.aggregateStatus(List.of(
                new VdpPageResult(0, "", VdpPageStatus.SUCCESS, Map.of()),
                new VdpPageResult(1, "", VdpPageStatus.FAILED, Map.of())
        ), null)).isEqualTo("FAILED");

        assertThat(VdpMetricsSupport.aggregateStatus(List.of(
                new VdpPageResult(0, "", VdpPageStatus.SUCCESS, Map.of())
        ), null)).isEqualTo("SUCCESS");
    }

    @Test
    void shouldIgnoreOddTagArraysWithoutAffectingBusinessFlow() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Timer.Sample sample = VdpMetricsSupport.start(meterRegistry);

        assertThatCode(() -> VdpMetricsSupport.increment(meterRegistry, "vdp.test.counter", "odd"))
                .doesNotThrowAnyException();
        assertThatCode(() -> VdpMetricsSupport.stop(meterRegistry, sample, "vdp.test.timer", "odd"))
                .doesNotThrowAnyException();
        assertThat(meterRegistry.getMeters()).isEmpty();
    }
}
