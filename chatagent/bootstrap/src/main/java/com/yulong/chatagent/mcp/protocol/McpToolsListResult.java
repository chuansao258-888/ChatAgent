package com.yulong.chatagent.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Minimal {@code tools/list} result payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpToolsListResult(
        List<McpRemoteTool> tools
) {
}
