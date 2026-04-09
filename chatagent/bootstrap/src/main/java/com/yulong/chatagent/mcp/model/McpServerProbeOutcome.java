package com.yulong.chatagent.mcp.model;

import com.yulong.chatagent.support.dto.McpServerDTO;

/**
 * Persisted server snapshot plus one admin-side probe result.
 */
public record McpServerProbeOutcome(
        boolean success,
        String errorCode,
        String errorMessage,
        McpDiscoveryResult discoveryResult,
        McpServerDTO server
) {
}
