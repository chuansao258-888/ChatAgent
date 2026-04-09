package com.yulong.chatagent.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Remote MCP server identity returned from {@code initialize}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpRemoteServerInfo(
        String name,
        String version
) {
}
