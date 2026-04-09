package com.yulong.chatagent.support.enums;

import com.yulong.chatagent.exception.BizException;

/**
 * Severity level for persisted MCP alert events.
 */
public enum McpAlertSeverity {
    WARNING,
    ERROR;

    public static McpAlertSeverity fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException("MCP alert severity is required");
        }
        try {
            return McpAlertSeverity.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException("Unsupported MCP alert severity: " + value);
        }
    }
}
