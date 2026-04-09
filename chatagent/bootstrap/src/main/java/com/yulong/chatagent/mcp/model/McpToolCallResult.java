package com.yulong.chatagent.mcp.model;

/**
 * Raw result returned from one remote MCP tool call.
 */
public record McpToolCallResult(
        String payload
) {
}
