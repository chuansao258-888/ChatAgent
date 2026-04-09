package com.yulong.chatagent.mcp.model;

import com.yulong.chatagent.support.dto.McpServerDTO;

/**
 * Result of one background schema-drift detection pass.
 */
public record McpSchemaDriftOutcome(
        boolean success,
        boolean driftDetected,
        int staleToolCount,
        String errorCode,
        String message,
        McpServerDTO server
) {
}
