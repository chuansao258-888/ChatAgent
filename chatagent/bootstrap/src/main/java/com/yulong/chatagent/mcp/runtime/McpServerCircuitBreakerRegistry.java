package com.yulong.chatagent.mcp.runtime;

import com.yulong.chatagent.mcp.metrics.McpMetricsRecorder;
import com.yulong.chatagent.support.dto.McpServerDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds one runtime circuit breaker per MCP server.
 */
@Component
public class McpServerCircuitBreakerRegistry {

    private final McpRuntimeProtectionProperties properties;
    private final McpMetricsRecorder metricsRecorder;
    private final ConcurrentHashMap<String, McpServerCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    public McpServerCircuitBreakerRegistry(McpRuntimeProtectionProperties properties,
                                           McpMetricsRecorder metricsRecorder) {
        this.properties = properties;
        this.metricsRecorder = metricsRecorder;
    }

    public McpServerCircuitBreaker get(McpServerDTO server) {
        if (server == null || !StringUtils.hasText(server.getId())) {
            return new McpServerCircuitBreaker(properties);
        }
        return circuitBreakers.computeIfAbsent(server.getId(), ignored -> {
            McpServerCircuitBreaker circuitBreaker = new McpServerCircuitBreaker(properties);
            metricsRecorder.registerCircuitBreaker(server, circuitBreaker);
            return circuitBreaker;
        });
    }
}
