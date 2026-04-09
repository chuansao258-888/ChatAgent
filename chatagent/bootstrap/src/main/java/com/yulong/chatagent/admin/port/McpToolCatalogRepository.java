package com.yulong.chatagent.admin.port;

import com.yulong.chatagent.support.dto.McpToolCatalogDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Persistence port for cached MCP tool catalog rows.
 */
public interface McpToolCatalogRepository {

    List<McpToolCatalogDTO> findByServerId(String serverId);

    boolean upsert(McpToolCatalogDTO toolCatalog);

    int markMissingAsStale(String serverId,
                           List<String> activeRemoteOriginalNames,
                           LocalDateTime lastSyncedAt,
                           LocalDateTime updatedAt);

    boolean softDeleteByServerId(String serverId, LocalDateTime deletedAt, LocalDateTime updatedAt);
}
