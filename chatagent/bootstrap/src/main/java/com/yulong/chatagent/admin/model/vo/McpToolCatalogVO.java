package com.yulong.chatagent.admin.model.vo;

import com.yulong.chatagent.support.enums.McpToolCatalogStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Safe view of one MCP tool catalog row.
 */
@Data
@Builder
public class McpToolCatalogVO {
    private String id;
    private String serverId;
    private String remoteOriginalName;
    private String toolDescription;
    private String exposedModelName;
    private McpToolCatalogStatus status;
    private String effectPolicy;
    private Long policyVersion;
    private LocalDateTime lastSyncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
