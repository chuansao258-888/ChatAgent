package com.yulong.chatagent.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Minimal JSON-RPC response envelope used by the MCP transport client.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpJsonRpcResponse(
        JsonNode id,
        JsonNode result,
        McpJsonRpcError error
) {
}
