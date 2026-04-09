package com.yulong.chatagent.support.enums;

import com.yulong.chatagent.exception.BizException;

/**
 * Declares how encrypted MCP credentials should be injected into outbound requests.
 */
public enum McpAuthType {
    NONE,
    API_KEY,
    BEARER_TOKEN,
    OAUTH2_CLIENT;

    public static McpAuthType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        try {
            return McpAuthType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException("Unsupported MCP auth type: " + value);
        }
    }
}
