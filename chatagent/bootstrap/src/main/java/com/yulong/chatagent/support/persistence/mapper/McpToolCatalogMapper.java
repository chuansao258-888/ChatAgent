package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Mapper for {@code t_mcp_tool_catalog}.
 */
@Mapper
public interface McpToolCatalogMapper {

    List<McpToolCatalogDTO> selectByServerId(@Param("serverId") String serverId);

    int upsert(McpToolCatalogDTO toolCatalog);

    int updateEffectPolicy(@Param("toolId") String toolId,
                           @Param("effectPolicy") String effectPolicy,
                           @Param("expectedPolicyVersion") long expectedPolicyVersion,
                           @Param("updatedAt") LocalDateTime updatedAt);

    int markMissingAsStale(@Param("serverId") String serverId,
                           @Param("activeRemoteOriginalNames") List<String> activeRemoteOriginalNames,
                           @Param("lastSyncedAt") LocalDateTime lastSyncedAt,
                           @Param("updatedAt") LocalDateTime updatedAt);

    int softDeleteByServerId(@Param("serverId") String serverId,
                             @Param("deletedAt") LocalDateTime deletedAt,
                             @Param("updatedAt") LocalDateTime updatedAt);
}
