package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence entity for {@code t_mcp_alert_event}.
 */
@Data
@Builder
public class McpAlertEvent {
    private String id;
    private String serverId;
    private String serverSlug;
    private String toolName;
    private String alertType;
    private String severity;
    private String status;
    private String summary;
    private String detailsJson;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
