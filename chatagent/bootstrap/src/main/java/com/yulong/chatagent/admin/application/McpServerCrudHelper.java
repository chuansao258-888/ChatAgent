package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.CreateMcpServerRequest;
import com.yulong.chatagent.admin.model.request.UpdateMcpServerRequest;
import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.exception.ClientException;
import com.yulong.chatagent.mcp.application.McpCredentialCipher;
import com.yulong.chatagent.mcp.application.McpServerStatusMachine;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Extracts CRUD DTO assembly and persistence logic from
 * {@link McpServerAdminFacadeServiceImpl}.
 */
@Component
class McpServerCrudHelper {

    private static final String MCP_SERVER_SLUG_CONSTRAINT = "uq_t_mcp_server_slug";

    private final McpEndpointValidator endpointValidator;
    private final McpCredentialCipher credentialCipher;
    private final McpServerStatusMachine statusMachine;
    private final McpServerRepository mcpServerRepository;

    McpServerCrudHelper(McpEndpointValidator endpointValidator,
                        McpCredentialCipher credentialCipher,
                        McpServerStatusMachine statusMachine,
                        McpServerRepository mcpServerRepository) {
        this.endpointValidator = endpointValidator;
        this.credentialCipher = credentialCipher;
        this.statusMachine = statusMachine;
        this.mcpServerRepository = mcpServerRepository;
    }

    // ---- public API -------------------------------------------------------

    public McpServerDTO buildCreateDTO(CreateMcpServerRequest request) {
        String slug = requireSlug(request == null ? null : request.getSlug());
        McpProtocol protocol = McpProtocol.fromValue(request == null ? null : request.getProtocol());
        McpAuthType authType = McpAuthType.fromValue(request == null ? null : request.getAuthType());
        String endpointUrl = endpointValidator.validateAndNormalize(protocol, request == null ? null : request.getEndpointUrl());
        McpCredentialCipher.EncryptedCredential encrypted = credentialCipher.encrypt(request == null ? null : request.getCredentials());
        LocalDateTime now = LocalDateTime.now();

        return McpServerDTO.builder()
                .id(UUID.randomUUID().toString())
                .slug(slug)
                .name(requireName(request == null ? null : request.getName()))
                .description(normalizeNullable(request == null ? null : request.getDescription()))
                .protocol(protocol)
                .authType(authType)
                .endpointUrl(endpointUrl)
                .encryptedCredentials(encrypted.ciphertext())
                .credentialKeyVersion(encrypted.keyVersion())
                .status(statusMachine.initialStatus())
                .consecutiveFailures(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public McpServerDTO buildUpdateDTO(McpServerDTO existing, UpdateMcpServerRequest request) {
        String slug = request != null && request.getSlug() != null ? requireSlug(request.getSlug()) : existing.getSlug();
        McpProtocol protocol = request != null && request.getProtocol() != null
                ? McpProtocol.fromValue(request.getProtocol())
                : existing.getProtocol();
        McpAuthType authType = request != null && request.getAuthType() != null
                ? McpAuthType.fromValue(request.getAuthType())
                : existing.getAuthType();
        String endpointUrl = request != null && request.getEndpointUrl() != null
                ? endpointValidator.validateAndNormalize(protocol, request.getEndpointUrl())
                : existing.getEndpointUrl();

        String encryptedCredentials = existing.getEncryptedCredentials();
        String credentialKeyVersion = existing.getCredentialKeyVersion();
        boolean credentialsChanged = request != null && request.getCredentials() != null;
        if (credentialsChanged) {
            McpCredentialCipher.EncryptedCredential encrypted = credentialCipher.encrypt(request.getCredentials());
            encryptedCredentials = encrypted.ciphertext();
            credentialKeyVersion = encrypted.keyVersion();
        }

        boolean sensitiveChanged = !existing.getSlug().equals(slug)
                || existing.getProtocol() != protocol
                || existing.getAuthType() != authType
                || !existing.getEndpointUrl().equals(endpointUrl)
                || credentialsChanged;

        return McpServerDTO.builder()
                .id(existing.getId())
                .slug(slug)
                .name(request != null && request.getName() != null ? requireName(request.getName()) : existing.getName())
                .description(request != null && request.getDescription() != null
                        ? normalizeNullable(request.getDescription())
                        : existing.getDescription())
                .protocol(protocol)
                .authType(authType)
                .endpointUrl(endpointUrl)
                .encryptedCredentials(encryptedCredentials)
                .credentialKeyVersion(credentialKeyVersion)
                .status(sensitiveChanged ? statusMachine.markSensitiveConfigChanged(existing.getStatus()) : existing.getStatus())
                .consecutiveFailures(existing.getConsecutiveFailures())
                .lastTestedAt(existing.getLastTestedAt())
                .lastInitializedAt(existing.getLastInitializedAt())
                .lastSyncAt(existing.getLastSyncAt())
                .lastErrorCode(existing.getLastErrorCode())
                .lastErrorMessage(existing.getLastErrorMessage())
                .createdAt(existing.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public McpServerDTO requireServer(String serverId) {
        McpServerDTO server = mcpServerRepository.findById(serverId);
        if (server == null) {
            throw new ClientException(BaseErrorCode.NOT_FOUND, "MCP server not found: " + serverId);
        }
        return server;
    }

    public void ensureSlugAvailable(String slug, String currentServerId) {
        McpServerDTO existing = mcpServerRepository.findBySlug(slug);
        if (existing != null && !existing.getId().equals(currentServerId)) {
            throw new ClientException(BaseErrorCode.CONFLICT, "MCP server slug already exists: " + slug);
        }
    }

    public void saveServerOrThrow(McpServerDTO server) {
        try {
            if (!mcpServerRepository.save(server)) {
                throw new BizException("Failed to create MCP server");
            }
        } catch (DataIntegrityViolationException e) {
            throw translatePersistenceException(e, server == null ? null : server.getSlug());
        }
    }

    public void updateServerOrThrow(McpServerDTO server) {
        try {
            if (!mcpServerRepository.update(server)) {
                throw new BizException("Failed to update MCP server: " + (server == null ? null : server.getId()));
            }
        } catch (DataIntegrityViolationException e) {
            throw translatePersistenceException(e, server == null ? null : server.getSlug());
        }
    }

    // ---- private helpers --------------------------------------------------

    private String requireSlug(String slug) {
        String normalized = normalizeNullable(slug);
        if (!StringUtils.hasText(normalized)) {
            throw new BizException("MCP server slug is required");
        }
        if (!normalized.matches("^[a-z0-9_]+$")) {
            throw new BizException("MCP server slug must match ^[a-z0-9_]+$");
        }
        return normalized;
    }

    private String requireName(String name) {
        String normalized = normalizeNullable(name);
        if (!StringUtils.hasText(normalized)) {
            throw new BizException("MCP server name is required");
        }
        return normalized;
    }

    private String normalizeNullable(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private RuntimeException translatePersistenceException(DataIntegrityViolationException e, String slug) {
        if (isSlugConflict(e)) {
            return new ClientException(BaseErrorCode.CONFLICT, "MCP server slug already exists: " + slug, e);
        }
        return e;
    }

    private boolean isSlugConflict(DataIntegrityViolationException e) {
        if (e == null) {
            return false;
        }
        Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(e);
        String message = rootCause == null ? e.getMessage() : rootCause.getMessage();
        return StringUtils.hasText(message) && message.contains(MCP_SERVER_SLUG_CONSTRAINT);
    }
}
