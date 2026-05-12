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

    // MeterRegistry 可能不存在；这种情况下 RoutingMetrics 会退化成 no-op，不影响主流程。
    private final MeterRegistry meterRegistry;

    @Autowired
    public RoutingMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(meterRegistryProvider.getIfAvailable());
    }

    private RoutingMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    static RoutingMetrics noop() {
        // 给无法注入 RoutingMetrics 的类提供安全默认值。
        return new RoutingMetrics((MeterRegistry) null);
    }

    void recordAttempt(String mode, String modelId, String outcome, long durationMs, boolean fallbackAvailable) {
        // 记录一次模型尝试：同步调用、首包探测成功/失败、提交失败等都会进入这里。
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
        // 记录断路器是否跳过/拒绝某个模型，例如 denied_open、skipped_stream。
        increment(
                "chatagent.llm.circuit.decisions",
                "model", modelId,
                "decision", decision
        );
    }

    void recordCircuitEvent(String modelId, String event) {
        // 记录断路器状态变化事件，例如 opened、half_open、closed、reopened。
        increment(
                "chatagent.llm.circuit.events",
                "model", modelId,
                "event", event
        );
    }

    private void increment(String name, String... tags) {
        // 没有 MeterRegistry 时静默跳过，避免观测系统影响业务路由。
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
            // 防御性地把负数耗时归零，避免计时器拒绝异常值。
            meterRegistry.timer(name, tags).record(Math.max(durationMs, 0L), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Failed to record routing timer metric: name={}, error={}", name, e.getMessage());
        }
    }
}
