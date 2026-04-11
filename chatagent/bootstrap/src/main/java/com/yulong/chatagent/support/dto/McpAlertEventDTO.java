package com.yulong.chatagent.support.dto;

import com.yulong.chatagent.support.enums.McpAlertSeverity;
import com.yulong.chatagent.support.enums.McpAlertStatus;
import com.yulong.chatagent.support.enums.McpAlertType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Persisted MCP alert event visible to admin surfaces.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpAlertEventDTO {
    private String id;
    private String serverId;
    private String serverSlug;
    private String toolName;
    private McpAlertType alertType;
    private McpAlertSeverity severity;
    private McpAlertStatus status;
    private String summary;
    private String detailsJson;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
