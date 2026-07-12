package com.yulong.chatagent.support.dto;

import com.yulong.chatagent.support.enums.McpToolCatalogStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Cached remote MCP tool descriptor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolCatalogDTO {
    private String id;
    private String serverId;
    private String remoteOriginalName;
    private String toolDescription;
    private String exposedModelName;
    private String schemaJson;
    private String schemaHash;
    private String outputSchemaJson;
    private String annotationsJson;
    private String descriptorHash;
    private String effectPolicy;
    private Long policyVersion;
    private McpToolCatalogStatus status;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastSyncedAt;
}
