package com.yulong.chatagent.support.persistence.adapter.admin;

import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import com.yulong.chatagent.support.enums.McpServerStatus;
import com.yulong.chatagent.support.persistence.entity.McpServer;
import com.yulong.chatagent.support.persistence.mapper.McpServerMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis-backed MCP server repository.
 */
@Repository
public class MyBatisMcpServerRepository implements McpServerRepository {

    private final McpServerMapper mapper;

    public MyBatisMcpServerRepository(McpServerMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<McpServerDTO> findAll() {
        List<McpServerDTO> result = new ArrayList<>();
        for (McpServer server : mapper.selectAll()) {
            result.add(toDTO(server));
        }
        return result;
    }

    @Override
    public McpServerDTO findById(String id) {
        return toDTO(mapper.selectById(id));
    }

    @Override
    public McpServerDTO findBySlug(String slug) {
        return toDTO(mapper.selectBySlug(slug));
    }

    @Override
    public boolean save(McpServerDTO server) {
        return mapper.insert(toEntity(server)) > 0;
    }

    @Override
    public boolean update(McpServerDTO server) {
        return mapper.updateById(toEntity(server)) > 0;
    }

    @Override
    public boolean softDelete(String id, LocalDateTime deletedAt, LocalDateTime updatedAt) {
        return mapper.softDeleteById(id, deletedAt, updatedAt) > 0;
    }

    private McpServerDTO toDTO(McpServer entity) {
        if (entity == null) {
            return null;
        }
        return McpServerDTO.builder()
                .id(entity.getId())
                .slug(entity.getSlug())
                .name(entity.getName())
                .description(entity.getDescription())
                .protocol(McpProtocol.fromValue(entity.getProtocol()))
                .authType(McpAuthType.fromValue(entity.getAuthType()))
                .endpointUrl(entity.getEndpointUrl())
                .encryptedCredentials(entity.getEncryptedCredentials())
                .credentialKeyVersion(entity.getCredentialKeyVersion())
                .status(McpServerStatus.fromValue(entity.getStatus()))
                .consecutiveFailures(entity.getConsecutiveFailures())
                .lastTestedAt(entity.getLastTestedAt())
                .lastInitializedAt(entity.getLastInitializedAt())
                .lastSyncAt(entity.getLastSyncAt())
                .lastErrorCode(entity.getLastErrorCode())
                .lastErrorMessage(entity.getLastErrorMessage())
                .deletedAt(entity.getDeletedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private McpServer toEntity(McpServerDTO dto) {
        if (dto == null) {
            return null;
        }
        return McpServer.builder()
                .id(dto.getId())
                .slug(dto.getSlug())
                .name(dto.getName())
                .description(dto.getDescription())
                .protocol(dto.getProtocol() == null ? null : dto.getProtocol().name())
                .authType(dto.getAuthType() == null ? null : dto.getAuthType().name())
                .endpointUrl(dto.getEndpointUrl())
                .encryptedCredentials(dto.getEncryptedCredentials())
                .credentialKeyVersion(dto.getCredentialKeyVersion())
                .status(dto.getStatus() == null ? null : dto.getStatus().name())
                .consecutiveFailures(dto.getConsecutiveFailures())
                .lastTestedAt(dto.getLastTestedAt())
                .lastInitializedAt(dto.getLastInitializedAt())
                .lastSyncAt(dto.getLastSyncAt())
                .lastErrorCode(dto.getLastErrorCode())
                .lastErrorMessage(dto.getLastErrorMessage())
                .deletedAt(dto.getDeletedAt())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }
}
