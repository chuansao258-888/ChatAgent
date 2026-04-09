package com.yulong.chatagent.support.enums;

import com.yulong.chatagent.exception.BizException;

/**
 * Runtime exposure mode for MCP tools during staged rollout.
 */
public enum McpRolloutMode {
    ALL,
    NONE,
    AGENT_ALLOWLIST;

    public static McpRolloutMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException("MCP rollout mode is required");
        }
        try {
            return McpRolloutMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException("Unsupported MCP rollout mode: " + value);
        }
    }
}
