package com.yulong.chatagent.mcp.metrics;

import com.yulong.chatagent.mcp.runtime.McpRuntimeProtectionProperties;
import com.yulong.chatagent.mcp.runtime.McpServerCircuitBreaker;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import com.yulong.chatagent.support.enums.McpServerStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class McpMetricsRecorderTest {

    @Test
    void shouldRecordRuntimeCountersTimersAndCircuitGauge() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("meterRegistry", meterRegistry);
        McpMetricsRecorder recorder = new McpMetricsRecorder(beanFactory.getBeanProvider(MeterRegistry.class));
        McpServerDTO server = McpServerDTO.builder()
                .id("srv-1")
                .slug("google")
                .protocol(McpProtocol.HTTP)
                .authType(McpAuthType.NONE)
                .status(McpServerStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        recorder.recordSuccess(server, "mcp_google_search", 123);
        recorder.recordFailure(server, "mcp_google_search", "MCP_TIMEOUT", 456);
        recorder.recordRateLimited(server, "mcp_google_search");
        recorder.recordSchemaDrift(server, 2);

        McpServerCircuitBreaker circuitBreaker = new McpServerCircuitBreaker(new McpRuntimeProtectionProperties());
        recorder.registerCircuitBreaker(server, circuitBreaker);

        assertThat(meterRegistry.get("chatagent.mcp.calls").tag("outcome", "success").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("chatagent.mcp.failures").tag("error_code", "MCP_TIMEOUT").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("chatagent.mcp.rate_limited").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("chatagent.mcp.schema_drift").counter().count()).isEqualTo(2.0d);
        long totalLatencyCount = meterRegistry.find("chatagent.mcp.latency").timers().stream()
                .mapToLong(timer -> timer.count())
                .sum();
        assertThat(totalLatencyCount).isEqualTo(2L);
        assertThat(meterRegistry.get("chatagent.mcp.circuit.state").gauge().value()).isEqualTo(0.0d);
        McpServerMetricsSnapshot snapshot = recorder.snapshot(server);
        assertThat(snapshot.totalCalls()).isEqualTo(3L);
        assertThat(snapshot.successCount()).isEqualTo(1L);
        assertThat(snapshot.failureCount()).isEqualTo(1L);
        assertThat(snapshot.rateLimitedCount()).isEqualTo(1L);
        assertThat(snapshot.avgLatencyMs()).isEqualTo(290L);
        assertThat(snapshot.errorRate()).isEqualTo(33.3d);
    }
}
