package com.yulong.chatagent.support.enums;

import com.yulong.chatagent.exception.BizException;

/**
 * Lifecycle state of one cached MCP tool definition.
 */
public enum McpToolCatalogStatus {
    ENABLED,
    DISABLED,
    STALE;

    public static McpToolCatalogStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException("MCP tool catalog status is required");
        }
        try {
            return McpToolCatalogStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException("Unsupported MCP tool catalog status: " + value);
        }
    }
}
