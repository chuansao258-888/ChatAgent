package com.yulong.chatagent.admin.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Dashboard performance payload.
 */
@Data
@Builder
public class DashboardPerformanceVO {

    private String window;

    private Long avgLatencyMs;

    private Long p95LatencyMs;

    private Double successRate;

    private Double errorRate;

    private Double noDocRate;

    private Double slowRate;

    private DashboardMcpPerformanceVO mcp;
}
