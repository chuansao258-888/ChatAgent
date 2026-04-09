package com.yulong.chatagent.mcp.model;

import com.yulong.chatagent.support.dto.McpServerDTO;

/**
 * Result of one catalog sync plus the updated server snapshot.
 */
public record McpCatalogSyncOutcome(
        boolean success,
        String errorCode,
        String errorMessage,
        McpDiscoveryResult discoveryResult,
        McpServerDTO server,
        int createdCount,
        int updatedCount,
        int staleCount
) {
}
