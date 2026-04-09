package com.yulong.chatagent.support.persistence.adapter.admin;

import com.yulong.chatagent.admin.port.McpToolCatalogRepository;
import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import com.yulong.chatagent.support.enums.McpToolCatalogStatus;
import com.yulong.chatagent.support.persistence.entity.McpToolCatalog;
import com.yulong.chatagent.support.persistence.mapper.McpToolCatalogMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
        List<McpToolCatalogDTO> result = new ArrayList<>();
        for (McpToolCatalog row : mapper.selectByServerId(serverId)) {
            result.add(toDTO(row));
        }
        return result;
    }

    @Override
    public boolean upsert(McpToolCatalogDTO toolCatalog) {
        return mapper.upsert(toEntity(toolCatalog)) > 0;
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

    private McpToolCatalogDTO toDTO(McpToolCatalog entity) {
        if (entity == null) {
            return null;
        }
        return McpToolCatalogDTO.builder()
                .id(entity.getId())
                .serverId(entity.getServerId())
                .remoteOriginalName(entity.getRemoteOriginalName())
                .toolDescription(entity.getToolDescription())
                .exposedModelName(entity.getExposedModelName())
                .schemaJson(entity.getSchemaJson())
                .schemaHash(entity.getSchemaHash())
                .status(McpToolCatalogStatus.fromValue(entity.getStatus()))
                .deletedAt(entity.getDeletedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .lastSyncedAt(entity.getLastSyncedAt())
                .build();
    }

    private McpToolCatalog toEntity(McpToolCatalogDTO dto) {
        if (dto == null) {
            return null;
        }
        return McpToolCatalog.builder()
                .id(dto.getId())
                .serverId(dto.getServerId())
                .remoteOriginalName(dto.getRemoteOriginalName())
                .toolDescription(dto.getToolDescription())
                .exposedModelName(dto.getExposedModelName())
                .schemaJson(dto.getSchemaJson())
                .schemaHash(dto.getSchemaHash())
                .status(dto.getStatus() == null ? null : dto.getStatus().name())
                .deletedAt(dto.getDeletedAt())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .lastSyncedAt(dto.getLastSyncedAt())
                .build();
    }
}
