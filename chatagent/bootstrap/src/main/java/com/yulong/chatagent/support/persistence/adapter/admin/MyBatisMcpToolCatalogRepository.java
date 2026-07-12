package com.yulong.chatagent.support.persistence.adapter.admin;

import com.yulong.chatagent.admin.port.McpToolCatalogRepository;
import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import com.yulong.chatagent.support.persistence.mapper.McpToolCatalogMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MyBatis-backed MCP tool catalog repository.
 */
@Repository
public class MyBatisMcpToolCatalogRepository implements McpToolCatalogRepository {

    private final McpToolCatalogMapper mapper;

    public MyBatisMcpToolCatalogRepository(McpToolCatalogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<McpToolCatalogDTO> findByServerId(String serverId) {
        return mapper.selectByServerId(serverId);
    }

    @Override
    public boolean upsert(McpToolCatalogDTO toolCatalog) {
        return mapper.upsert(toolCatalog) > 0;
    }

    @Override
    public boolean updateEffectPolicy(String toolId, String effectPolicy,
                                      long expectedPolicyVersion, LocalDateTime updatedAt) {
        return mapper.updateEffectPolicy(toolId, effectPolicy, expectedPolicyVersion, updatedAt) == 1;
    }

    @Override
    public int markMissingAsStale(String serverId,
                                  List<String> activeRemoteOriginalNames,
                                  LocalDateTime lastSyncedAt,
                                  LocalDateTime updatedAt) {
        return mapper.markMissingAsStale(serverId, activeRemoteOriginalNames, lastSyncedAt, updatedAt);
    }

    @Override
    public boolean softDeleteByServerId(String serverId, LocalDateTime deletedAt, LocalDateTime updatedAt) {
        return mapper.softDeleteByServerId(serverId, deletedAt, updatedAt) >= 0;
    }
}
