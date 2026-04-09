package com.yulong.chatagent.admin.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * MCP alert feed payload for the admin dashboard.
 */
@Data
@Builder
public class DashboardMcpAlertsVO {
    private Long openAlertCount;
    private List<DashboardMcpAlertVO> alerts;
}
