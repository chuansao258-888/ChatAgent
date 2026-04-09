package com.yulong.chatagent.support.enums;

import com.yulong.chatagent.exception.BizException;

/**
 * Persistent lifecycle states for configured MCP servers.
 */
public enum McpServerStatus {
    ACTIVE,
    DISABLED,
    FAILED,
    STALE;

    public static McpServerStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException("MCP server status is required");
        }
        try {
            return McpServerStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException("Unsupported MCP server status: " + value);
        }
    }
}
