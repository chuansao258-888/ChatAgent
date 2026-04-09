package com.yulong.chatagent.admin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight admin-facing view of one discovered remote MCP tool.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpDiscoveredToolVO {
    private String remoteOriginalName;
    private String exposedModelName;
    private String toolDescription;
    private String schemaHash;
}
