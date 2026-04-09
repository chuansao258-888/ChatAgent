package com.yulong.chatagent.admin.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * MCP-specific dashboard performance snapshot.
 */
@Data
@Builder
public class DashboardMcpPerformanceVO {
    private Boolean enabled;
    private String rolloutMode;
    private Integer allowedAgentCount;
    private Long openAlertCount;
    private Integer serverCount;
    private List<DashboardMcpServerMetricVO> servers;
}
