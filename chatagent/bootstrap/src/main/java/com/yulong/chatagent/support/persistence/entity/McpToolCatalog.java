package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence entity for {@code t_mcp_tool_catalog}.
 */
@Data
@Builder
public class McpToolCatalog {
    private String id;
    private String serverId;
    private String remoteOriginalName;
    private String toolDescription;
    private String exposedModelName;
    private String schemaJson;
    private String schemaHash;
    private String status;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastSyncedAt;
}
