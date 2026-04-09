package com.yulong.chatagent.chat.routing;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RoutingMetrics {

    private final MeterRegistry meterRegistry;

    @Autowired
    public RoutingMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(meterRegistryProvider.getIfAvailable());
    }

    private RoutingMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    static RoutingMetrics noop() {
        return new RoutingMetrics((MeterRegistry) null);
    }

    void recordAttempt(String mode, String modelId, String outcome, long durationMs, boolean fallbackAvailable) {
        increment(
                "chatagent.llm.routing.attempts",
                "mode", mode,
                "model", modelId,
                "outcome", outcome,
                "fallback_available", Boolean.toString(fallbackAvailable)
        );
        recordTimer(
                "chatagent.llm.routing.latency",
                durationMs,
                "mode", mode,
                "model", modelId,
                "outcome", outcome
        );
    }

    void recordCircuitDecision(String modelId, String decision) {
        increment(
                "chatagent.llm.circuit.decisions",
                "model", modelId,
                "decision", decision
        );
    }

    void recordCircuitEvent(String modelId, String event) {
        increment(
                "chatagent.llm.circuit.events",
                "model", modelId,
                "event", event
        );
    }

    private void increment(String name, String... tags) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(name, tags).increment();
        } catch (Exception e) {
            log.warn("Failed to increment routing metric: name={}, error={}", name, e.getMessage());
        }
    }

    private void recordTimer(String name, long durationMs, String... tags) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.timer(name, tags).record(Math.max(durationMs, 0L), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Failed to record routing timer metric: name={}, error={}", name, e.getMessage());
        }
    }
}
