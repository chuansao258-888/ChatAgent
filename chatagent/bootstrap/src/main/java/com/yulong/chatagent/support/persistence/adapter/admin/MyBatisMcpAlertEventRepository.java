package com.yulong.chatagent.support.persistence.adapter.admin;

import com.yulong.chatagent.admin.port.McpAlertEventRepository;
import com.yulong.chatagent.support.dto.McpAlertEventDTO;
import com.yulong.chatagent.support.enums.McpAlertSeverity;
import com.yulong.chatagent.support.enums.McpAlertStatus;
import com.yulong.chatagent.support.enums.McpAlertType;
import com.yulong.chatagent.support.persistence.entity.McpAlertEvent;
import com.yulong.chatagent.support.persistence.mapper.McpAlertEventMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis-backed MCP alert event repository.
 */
@Repository
public class MyBatisMcpAlertEventRepository implements McpAlertEventRepository {

    private final McpAlertEventMapper mapper;

    public MyBatisMcpAlertEventRepository(McpAlertEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean save(McpAlertEventDTO alertEvent) {
        return mapper.insert(toEntity(alertEvent)) > 0;
    }

    @Override
    public boolean update(McpAlertEventDTO alertEvent) {
        return mapper.updateById(toEntity(alertEvent)) > 0;
    }

    @Override
    public McpAlertEventDTO findOpenByServerAndType(String serverId, McpAlertType alertType) {
        return toDTO(mapper.selectOpenByServerAndType(serverId, alertType == null ? null : alertType.name()));
    }

    @Override
    public List<McpAlertEventDTO> findRecentOpen(int limit) {
        List<McpAlertEventDTO> result = new ArrayList<>();
        for (McpAlertEvent event : mapper.selectRecentOpen(limit)) {
            result.add(toDTO(event));
        }
        return result;
    }

    @Override
    public long countOpen() {
        Long count = mapper.countOpen();
        return count == null ? 0L : count;
    }

    @Override
    public int resolveOpenByServerAndType(String serverId, McpAlertType alertType, LocalDateTime resolvedAt, LocalDateTime updatedAt) {
        return mapper.resolveOpenByServerAndType(serverId, alertType == null ? null : alertType.name(), resolvedAt, updatedAt);
    }

    private McpAlertEventDTO toDTO(McpAlertEvent entity) {
        if (entity == null) {
            return null;
        }
        return McpAlertEventDTO.builder()
                .id(entity.getId())
                .serverId(entity.getServerId())
                .serverSlug(entity.getServerSlug())
                .toolName(entity.getToolName())
                .alertType(McpAlertType.fromValue(entity.getAlertType()))
                .severity(McpAlertSeverity.fromValue(entity.getSeverity()))
                .status(McpAlertStatus.fromValue(entity.getStatus()))
                .summary(entity.getSummary())
                .detailsJson(entity.getDetailsJson())
                .resolvedAt(entity.getResolvedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private McpAlertEvent toEntity(McpAlertEventDTO dto) {
        if (dto == null) {
            return null;
        }
        return McpAlertEvent.builder()
                .id(dto.getId())
                .serverId(dto.getServerId())
                .serverSlug(dto.getServerSlug())
                .toolName(dto.getToolName())
                .alertType(dto.getAlertType() == null ? null : dto.getAlertType().name())
                .severity(dto.getSeverity() == null ? null : dto.getSeverity().name())
                .status(dto.getStatus() == null ? null : dto.getStatus().name())
                .summary(dto.getSummary())
                .detailsJson(dto.getDetailsJson())
                .resolvedAt(dto.getResolvedAt())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }
}
