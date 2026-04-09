package com.yulong.chatagent.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Minimal initialize result payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpInitializeResult(
        String protocolVersion,
        McpRemoteServerInfo serverInfo,
        JsonNode capabilities
) {
}
