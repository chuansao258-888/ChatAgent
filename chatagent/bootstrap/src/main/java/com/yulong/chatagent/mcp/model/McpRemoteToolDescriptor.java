package com.yulong.chatagent.mcp.model;

/**
 * Normalized view of one remote MCP tool discovered from {@code tools/list}.
 */
public record McpRemoteToolDescriptor(
        String remoteOriginalName,
        String toolDescription,
        String schemaJson,
        String schemaHash
) {
}
