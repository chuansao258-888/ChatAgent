package com.yulong.chatagent.admin.model.vo;

import com.yulong.chatagent.support.enums.McpAlertSeverity;
import com.yulong.chatagent.support.enums.McpAlertType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Admin-facing MCP alert row.
 */
@Data
@Builder
public class DashboardMcpAlertVO {
    private String id;
    private String serverId;
    private String serverSlug;
    private String toolName;
    private McpAlertType alertType;
    private McpAlertSeverity severity;
    private String summary;
    private String detailsJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
