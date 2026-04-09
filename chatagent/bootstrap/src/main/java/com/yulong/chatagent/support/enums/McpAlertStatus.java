package com.yulong.chatagent.support.enums;

import com.yulong.chatagent.exception.BizException;

/**
 * Lifecycle state for persisted MCP alert events.
 */
public enum McpAlertStatus {
    OPEN,
    RESOLVED;

    public static McpAlertStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException("MCP alert status is required");
        }
        try {
            return McpAlertStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException("Unsupported MCP alert status: " + value);
        }
    }
}
