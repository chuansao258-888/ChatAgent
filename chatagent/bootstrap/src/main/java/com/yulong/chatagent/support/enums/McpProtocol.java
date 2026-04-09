package com.yulong.chatagent.support.enums;

import com.yulong.chatagent.exception.BizException;

/**
 * Supported outbound MCP transport styles.
 */
public enum McpProtocol {
    HTTP,
    SSE;

    public static McpProtocol fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException("MCP protocol is required");
        }
        try {
            return McpProtocol.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException("Unsupported MCP protocol: " + value);
        }
    }
}
