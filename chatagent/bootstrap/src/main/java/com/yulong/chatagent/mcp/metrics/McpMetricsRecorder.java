package com.yulong.chatagent.mcp.metrics;

import com.yulong.chatagent.mcp.runtime.McpServerCircuitBreaker;
import com.yulong.chatagent.support.dto.McpServerDTO;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized Micrometer recorder for MCP runtime signals.
 */
@Component
public class McpMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final Set<String> registeredCircuitGauges = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, InMemoryServerMetrics> snapshots = new ConcurrentHashMap<>();

    public McpMetricsRecorder(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    public void recordSuccess(McpServerDTO server, String toolName, long latencyMs) {
        metricsFor(server).recordSuccess(latencyMs);
        increment("chatagent.mcp.calls", server, toolName, "success", null);
        recordLatency(server, toolName, "success", latencyMs);
    }

    public void recordFailure(McpServerDTO server, String toolName, String errorCode, long latencyMs) {
        metricsFor(server).recordFailure(latencyMs);
        increment("chatagent.mcp.calls", server, toolName, "failure", null);
        increment("chatagent.mcp.failures", server, toolName, "failure", normalize(errorCode));
        recordLatency(server, toolName, "failure", latencyMs);
    }

    public void recordRateLimited(McpServerDTO server, String toolName) {
        metricsFor(server).recordRateLimited();
        increment("chatagent.mcp.rate_limited", server, toolName, "rate_limited", null);
    }

    public void recordSchemaDrift(McpServerDTO server, int staleToolCount) {
        if (meterRegistry == null || staleToolCount <= 0) {
            return;
        }
        meterRegistry.counter("chatagent.mcp.schema_drift",
                "server", serverTag(server),
                "protocol", protocolTag(server)
        ).increment(staleToolCount);
    }

    public void recordSchemaDriftFailure(McpServerDTO server, String errorCode) {
        increment("chatagent.mcp.schema_drift.failures", server, null, "failure", normalize(errorCode));
    }

    public void registerCircuitBreaker(McpServerDTO server, McpServerCircuitBreaker circuitBreaker) {
        if (server != null && circuitBreaker != null) {
            metricsFor(server).attachCircuitBreaker(circuitBreaker);
        }
        if (meterRegistry == null || server == null || circuitBreaker == null || !StringUtils.hasText(server.getId())) {
            return;
        }
        if (!registeredCircuitGauges.add(server.getId())) {
            return;
        }
        Gauge.builder("chatagent.mcp.circuit.state", circuitBreaker, McpServerCircuitBreaker::stateMetricValue)
                .description("0=CLOSED, 1=HALF_OPEN, 2=OPEN")
                .tags("server", serverTag(server), "protocol", protocolTag(server))
                .register(meterRegistry);
    }

    public McpServerMetricsSnapshot snapshot(McpServerDTO server) {
        String serverId = server == null ? null : server.getId();
        InMemoryServerMetrics metrics = StringUtils.hasText(serverId) ? snapshots.get(serverId) : null;
        if (metrics == null) {
            return McpServerMetricsSnapshot.builder()
                    .serverId(serverId)
                    .totalCalls(0L)
                    .successCount(0L)
                    .failureCount(0L)
                    .rateLimitedCount(0L)
                    .avgLatencyMs(0L)
                    .qps(0.0d)
                    .errorRate(0.0d)
                    .circuitState(0)
                    .build();
        }
        return metrics.snapshot(serverId);
    }

    private void increment(String metricName,
                           McpServerDTO server,
                           String toolName,
                           String outcome,
                           String errorCode) {
        if (meterRegistry == null) {
            return;
        }
        if (errorCode == null) {
            meterRegistry.counter(metricName,
                    "server", serverTag(server),
                    "tool", toolTag(toolName),
                    "protocol", protocolTag(server),
                    "outcome", normalize(outcome)
            ).increment();
            return;
        }
        meterRegistry.counter(metricName,
                "server", serverTag(server),
                "tool", toolTag(toolName),
                "protocol", protocolTag(server),
                "outcome", normalize(outcome),
                "error_code", normalize(errorCode)
        ).increment();
    }

    private void recordLatency(McpServerDTO server, String toolName, String outcome, long latencyMs) {
        if (meterRegistry == null) {
            return;
        }
        Timer.builder("chatagent.mcp.latency")
                .tags(
                        "server", serverTag(server),
                        "tool", toolTag(toolName),
                        "protocol", protocolTag(server),
                        "outcome", normalize(outcome)
                )
                .register(meterRegistry)
                .record(Duration.ofMillis(Math.max(0L, latencyMs)));
    }

    private String serverTag(McpServerDTO server) {
        if (server == null) {
            return "unknown";
        }
        if (StringUtils.hasText(server.getSlug())) {
            return server.getSlug().trim();
        }
        return normalize(server.getId());
    }

    private String protocolTag(McpServerDTO server) {
        if (server == null || server.getProtocol() == null) {
            return "unknown";
        }
        return normalize(server.getProtocol().name());
    }

    private String toolTag(String toolName) {
        return StringUtils.hasText(toolName) ? toolName.trim() : "unknown";
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : "unknown";
    }

    private InMemoryServerMetrics metricsFor(McpServerDTO server) {
        String serverId = server == null ? "unknown" : normalize(server.getId());
        return snapshots.computeIfAbsent(serverId, ignored -> new InMemoryServerMetrics());
    }

    private static final class InMemoryServerMetrics {

        private final AtomicLong totalCalls = new AtomicLong();
        private final AtomicLong successCount = new AtomicLong();
        private final AtomicLong failureCount = new AtomicLong();
        private final AtomicLong rateLimitedCount = new AtomicLong();
        private final AtomicLong totalLatencyMs = new AtomicLong();
        private final AtomicLong latencySamples = new AtomicLong();
        private final AtomicLong firstCallAtMillis = new AtomicLong();
        private volatile McpServerCircuitBreaker circuitBreaker;

        void recordSuccess(long latencyMs) {
            recordCall(latencyMs);
            successCount.incrementAndGet();
        }

        void recordFailure(long latencyMs) {
            recordCall(latencyMs);
            failureCount.incrementAndGet();
        }

        void recordRateLimited() {
            long now = System.currentTimeMillis();
            totalCalls.incrementAndGet();
            rateLimitedCount.incrementAndGet();
            firstCallAtMillis.compareAndSet(0L, now);
        }

        void attachCircuitBreaker(McpServerCircuitBreaker circuitBreaker) {
            this.circuitBreaker = circuitBreaker;
        }

        McpServerMetricsSnapshot snapshot(String serverId) {
            long total = totalCalls.get();
            long failure = failureCount.get();
            long samples = latencySamples.get();
            long avgLatency = samples <= 0 ? 0L : Math.round(totalLatencyMs.get() / (double) samples);
            long firstSeen = firstCallAtMillis.get();
            double elapsedSeconds = firstSeen <= 0 ? 0.0d : Math.max(1.0d, (System.currentTimeMillis() - firstSeen) / 1000.0d);
            return McpServerMetricsSnapshot.builder()
                    .serverId(serverId)
                    .totalCalls(total)
                    .successCount(successCount.get())
                    .failureCount(failure)
                    .rateLimitedCount(rateLimitedCount.get())
                    .avgLatencyMs(avgLatency)
                    .qps(total <= 0 ? 0.0d : round(total / elapsedSeconds, 2))
                    .errorRate(total <= 0 ? 0.0d : round(failure * 100.0d / total, 1))
                    .circuitState((int) (circuitBreaker == null ? 0.0d : circuitBreaker.stateMetricValue()))
                    .build();
        }

        private void recordCall(long latencyMs) {
            long now = System.currentTimeMillis();
            totalCalls.incrementAndGet();
            totalLatencyMs.addAndGet(Math.max(0L, latencyMs));
            latencySamples.incrementAndGet();
            firstCallAtMillis.compareAndSet(0L, now);
        }

        private double round(double value, int scale) {
            return BigDecimal.valueOf(value)
                    .setScale(scale, RoundingMode.HALF_UP)
                    .doubleValue();
        }
    }
}
