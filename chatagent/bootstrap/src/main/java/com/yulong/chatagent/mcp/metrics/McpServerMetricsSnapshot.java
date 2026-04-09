package com.yulong.chatagent.mcp.metrics;

import lombok.Builder;

/**
 * Point-in-time runtime metrics snapshot for one MCP server.
 */
@Builder
public record McpServerMetricsSnapshot(
        String serverId,
        long totalCalls,
        long successCount,
        long failureCount,
        long rateLimitedCount,
        long avgLatencyMs,
        double qps,
        double errorRate,
        int circuitState
) {
}
