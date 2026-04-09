package com.yulong.chatagent.support.enums;

import com.yulong.chatagent.exception.BizException;

/**
 * Typed MCP alert categories exposed to admin visibility surfaces.
 */
public enum McpAlertType {
    SERVER_FAILED,
    SCHEMA_DRIFT,
    UNRESOLVED_REFERENCE;

    public static McpAlertType fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException("MCP alert type is required");
        }
        try {
            return McpAlertType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException("Unsupported MCP alert type: " + value);
        }
    }
}
