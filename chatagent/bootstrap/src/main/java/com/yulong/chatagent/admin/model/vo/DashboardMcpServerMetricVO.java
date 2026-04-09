package com.yulong.chatagent.admin.model.vo;

import com.yulong.chatagent.support.enums.McpServerStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP per-server dashboard snapshot.
 */
@Data
@Builder
public class DashboardMcpServerMetricVO {
    private String serverId;
    private String serverSlug;
    private String serverName;
    private McpServerStatus status;
    private Integer unresolvedReferenceCount;
    private Long totalCalls;
    private Long successCount;
    private Long failureCount;
    private Long rateLimitedCount;
    private Long avgLatencyMs;
    private Double qps;
    private Double errorRate;
    private Integer circuitState;
    private String lastErrorCode;
    private LocalDateTime lastTestedAt;
    private LocalDateTime lastSyncAt;
}
