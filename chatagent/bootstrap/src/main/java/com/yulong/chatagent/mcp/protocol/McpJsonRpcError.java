package com.yulong.chatagent.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Minimal JSON-RPC error envelope used by the MCP transport client.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpJsonRpcError(
        Integer code,
        String message
) {
}
