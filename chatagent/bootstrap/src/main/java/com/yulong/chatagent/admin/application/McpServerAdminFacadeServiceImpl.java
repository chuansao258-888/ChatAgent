package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.CreateMcpServerRequest;
import com.yulong.chatagent.admin.model.request.UpdateMcpServerRequest;
import com.yulong.chatagent.admin.model.response.DeleteMcpServerResponse;
import com.yulong.chatagent.admin.model.response.GetMcpServerResponse;
import com.yulong.chatagent.admin.model.response.ListMcpServersResponse;
import com.yulong.chatagent.admin.model.response.SyncMcpToolCatalogResponse;
import com.yulong.chatagent.admin.model.response.TestMcpServerResponse;
import com.yulong.chatagent.admin.model.vo.McpDiscoveredToolVO;
import com.yulong.chatagent.admin.model.vo.McpServerVO;
import com.yulong.chatagent.admin.model.vo.McpToolCatalogVO;
import com.yulong.chatagent.admin.model.vo.McpToolReferenceVO;
import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.admin.port.McpToolCatalogRepository;
import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.exception.ClientException;
import com.yulong.chatagent.intent.application.IntentTreeCacheManager;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.port.IntentKnowledgeBaseRepository;
import com.yulong.chatagent.intent.port.IntentNodeRepository;
import com.yulong.chatagent.mcp.application.McpCatalogSyncService;
import com.yulong.chatagent.mcp.application.McpServerTestService;
import com.yulong.chatagent.mcp.model.McpCatalogSyncOutcome;
import com.yulong.chatagent.mcp.model.McpRemoteToolDescriptor;
import com.yulong.chatagent.mcp.model.McpServerProbeOutcome;
import com.yulong.chatagent.mcp.runtime.McpRuntimeToolRegistry;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import com.yulong.chatagent.support.dto.McpToolReferenceDTO;
import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import com.yulong.chatagent.support.enums.McpReferenceType;
import com.yulong.chatagent.support.enums.McpServerStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 1a MCP admin CRUD plus delete-time reference checks.
 */
@Service
public class McpServerAdminFacadeServiceImpl implements McpServerAdminFacadeService {

    private static final String MCP_SERVER_SLUG_CONSTRAINT = "uq_t_mcp_server_slug";

    private final AdminAccessService adminAccessService;
    private final McpServerRepository mcpServerRepository;
    private final McpToolCatalogRepository mcpToolCatalogRepository;
    private final McpEndpointValidator endpointValidator;
    private final McpCredentialCipher credentialCipher;
    private final McpServerStatusMachine statusMachine;
    private final McpServerReferenceInspector referenceInspector;
    private final McpToolNameNormalizer toolNameNormalizer;
    private final McpServerTestService mcpServerTestService;
    private final McpCatalogSyncService mcpCatalogSyncService;
    private final McpRuntimeToolRegistry mcpRuntimeToolRegistry;
    private final McpAlertService mcpAlertService;
    private final IntentNodeRepository intentNodeRepository;
    private final IntentKnowledgeBaseRepository intentKnowledgeBaseRepository;
    private final IntentTreeCacheManager intentTreeCacheManager;

    public McpServerAdminFacadeServiceImpl(AdminAccessService adminAccessService,
                                           McpServerRepository mcpServerRepository,
                                           McpToolCatalogRepository mcpToolCatalogRepository,
                                           McpEndpointValidator endpointValidator,
                                           McpCredentialCipher credentialCipher,
                                           McpServerStatusMachine statusMachine,
                                           McpServerReferenceInspector referenceInspector,
                                           McpToolNameNormalizer toolNameNormalizer,
                                           McpServerTestService mcpServerTestService,
                                           McpCatalogSyncService mcpCatalogSyncService,
                                           McpRuntimeToolRegistry mcpRuntimeToolRegistry,
                                           McpAlertService mcpAlertService,
                                           IntentNodeRepository intentNodeRepository,
                                           IntentKnowledgeBaseRepository intentKnowledgeBaseRepository,
                                           IntentTreeCacheManager intentTreeCacheManager) {
        this.adminAccessService = adminAccessService;
        this.mcpServerRepository = mcpServerRepository;
        this.mcpToolCatalogRepository = mcpToolCatalogRepository;
        this.endpointValidator = endpointValidator;
        this.credentialCipher = credentialCipher;
        this.statusMachine = statusMachine;
        this.referenceInspector = referenceInspector;
        this.toolNameNormalizer = toolNameNormalizer;
        this.mcpServerTestService = mcpServerTestService;
        this.mcpCatalogSyncService = mcpCatalogSyncService;
        this.mcpRuntimeToolRegistry = mcpRuntimeToolRegistry;
        this.mcpAlertService = mcpAlertService;
        this.intentNodeRepository = intentNodeRepository;
        this.intentKnowledgeBaseRepository = intentKnowledgeBaseRepository;
        this.intentTreeCacheManager = intentTreeCacheManager;
    }

    @Override
    public ListMcpServersResponse getServers() {
        adminAccessService.requireAdmin();
        return new ListMcpServersResponse(
                mcpServerRepository.findAll().stream()
                        .map(this::toServerVO)
                        .toList()
        );
    }

    @Override
    public GetMcpServerResponse getServer(String serverId) {
        adminAccessService.requireAdmin();
        McpServerDTO server = requireServer(serverId);
        List<McpToolCatalogVO> catalog = mcpToolCatalogRepository.findByServerId(serverId).stream()
                .map(this::toCatalogVO)
                .toList();
        return new GetMcpServerResponse(toServerVO(server), catalog);
    }

    @Override
    @Transactional
    public String createServer(CreateMcpServerRequest request) {
        adminAccessService.requireAdmin();

        String slug = requireSlug(request == null ? null : request.getSlug());
        ensureSlugAvailable(slug, null);
        McpProtocol protocol = McpProtocol.fromValue(request == null ? null : request.getProtocol());
        McpAuthType authType = McpAuthType.fromValue(request == null ? null : request.getAuthType());
        String endpointUrl = endpointValidator.validateAndNormalize(protocol, request == null ? null : request.getEndpointUrl());
        McpCredentialCipher.EncryptedCredential encrypted = credentialCipher.encrypt(request == null ? null : request.getCredentials());
        LocalDateTime now = LocalDateTime.now();

        McpServerDTO server = McpServerDTO.builder()
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

        saveServerOrThrow(server);
        mcpRuntimeToolRegistry.invalidate();
        return server.getId();
    }

    @Override
    @Transactional
    public void updateServer(String serverId, UpdateMcpServerRequest request) {
        adminAccessService.requireAdmin();

        McpServerDTO existing = requireServer(serverId);
        String slug = request != null && request.getSlug() != null ? requireSlug(request.getSlug()) : existing.getSlug();
        ensureSlugAvailable(slug, existing.getId());

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

        McpServerDTO updated = McpServerDTO.builder()
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

        updateServerOrThrow(updated);
        mcpRuntimeToolRegistry.invalidate();
    }

    @Override
    @Transactional
    public DeleteMcpServerResponse deleteServer(String serverId, boolean force) {
        adminAccessService.requireAdmin();

        McpServerDTO server = requireServer(serverId);
        List<McpToolCatalogDTO> catalogRows = mcpToolCatalogRepository.findByServerId(serverId);
        List<String> toolNames = catalogRows.stream()
                .map(McpToolCatalogDTO::getExposedModelName)
                .filter(StringUtils::hasText)
                .toList();
        List<McpToolReferenceDTO> references = referenceInspector.inspect(toolNames);
        if (!force && !references.isEmpty()) {
            return new DeleteMcpServerResponse(
                    false,
                    false,
                    references.size(),
                    0,
                    references.stream().map(this::toReferenceVO).toList()
            );
        }
        int activeReferenceCount = references.size();
        if (force && !references.isEmpty()) {
            cleanupIntentNodeReferences(references, toolNames);
            references = referenceInspector.inspect(toolNames);
        }

        LocalDateTime now = LocalDateTime.now();
        if (!catalogRows.isEmpty()) {
            mcpToolCatalogRepository.softDeleteByServerId(serverId, now, now);
        }
        if (!mcpServerRepository.softDelete(serverId, now, now)) {
            throw new BizException("Failed to delete MCP server: " + serverId);
        }
        if (force && !references.isEmpty()) {
            mcpAlertService.raiseUnresolvedReference(
                    server.getId(),
                    server.getSlug(),
                    references.size(),
                    references.stream().map(McpToolReferenceDTO::getReferencePath).toList()
            );
        }
        mcpRuntimeToolRegistry.invalidate();
        return new DeleteMcpServerResponse(
                true,
                true,
                activeReferenceCount,
                references.size(),
                references.stream().map(this::toReferenceVO).toList()
        );
    }

    private void cleanupIntentNodeReferences(List<McpToolReferenceDTO> references, List<String> toolNames) {
        if (references == null || references.isEmpty() || toolNames == null || toolNames.isEmpty()) {
            return;
        }

        Set<String> removedToolNames = new LinkedHashSet<>(toolNames);
        Set<String> nodeIds = references.stream()
                .filter(reference -> reference != null && reference.getReferenceType() == McpReferenceType.INTENT_NODE)
                .map(McpToolReferenceDTO::getReferenceId)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (nodeIds.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Set<String> affectedAgentIds = new LinkedHashSet<>();
        for (String nodeId : nodeIds) {
            IntentNodeDTO node = intentNodeRepository.findById(nodeId);
            if (node == null) {
                continue;
            }

            List<String> currentAllowedTools = node.getAllowedTools() == null ? List.of() : node.getAllowedTools();
            List<String> remainingAllowedTools = currentAllowedTools.stream()
                    .filter(toolName -> !removedToolNames.contains(toolName))
                    .toList();
            if (remainingAllowedTools.size() == currentAllowedTools.size()) {
                continue;
            }

            if (StringUtils.hasText(node.getAgentId())) {
                affectedAgentIds.add(node.getAgentId());
            }

            if (shouldDeleteIntentNodeAfterCleanup(node, currentAllowedTools, remainingAllowedTools)) {
                intentKnowledgeBaseRepository.deleteByIntentNodeIds(List.of(nodeId));
                if (!intentNodeRepository.deleteByIds(List.of(nodeId))) {
                    throw new BizException("Failed to remove intent node reference during MCP delete: " + nodeId);
                }
                continue;
            }

            node.setAllowedTools(remainingAllowedTools);
            node.setUpdatedAt(now);
            if (!intentNodeRepository.update(node)) {
                throw new BizException("Failed to update intent node reference during MCP delete: " + nodeId);
            }
        }

        for (String agentId : affectedAgentIds) {
            intentTreeCacheManager.refreshActiveSnapshot(agentId);
        }
    }

    private boolean shouldDeleteIntentNodeAfterCleanup(IntentNodeDTO node,
                                                       List<String> currentAllowedTools,
                                                       List<String> remainingAllowedTools) {
        return node != null
                && node.getIntentKind() == IntentKind.TOOL
                && currentAllowedTools != null
                && !currentAllowedTools.isEmpty()
                && (remainingAllowedTools == null || remainingAllowedTools.isEmpty());
    }

    @Override
    public TestMcpServerResponse testServer(String serverId) {
        adminAccessService.requireAdmin();
        McpServerProbeOutcome outcome = mcpServerTestService.test(requireServer(serverId));
        mcpRuntimeToolRegistry.invalidate();
        return TestMcpServerResponse.builder()
                .success(outcome.success())
                .errorCode(outcome.errorCode())
                .errorMessage(outcome.errorMessage())
                .negotiatedProtocolVersion(outcome.discoveryResult() == null ? null : outcome.discoveryResult().negotiatedProtocolVersion())
                .remoteServerName(outcome.discoveryResult() == null ? null : outcome.discoveryResult().remoteServerName())
                .remoteServerVersion(outcome.discoveryResult() == null ? null : outcome.discoveryResult().remoteServerVersion())
                .discoveredToolCount(outcome.discoveryResult() == null || outcome.discoveryResult().tools() == null ? 0 : outcome.discoveryResult().tools().size())
                .discoveredTools(outcome.discoveryResult() == null || outcome.discoveryResult().tools() == null
                        ? List.of()
                        : outcome.discoveryResult().tools().stream().map(tool -> toDiscoveredToolVO(tool, outcome.server())).toList())
                .testedAt(outcome.server() == null ? null : outcome.server().getLastTestedAt())
                .server(outcome.server() == null ? null : toServerVO(outcome.server()))
                .build();
    }

    @Override
    public SyncMcpToolCatalogResponse syncServer(String serverId) {
        adminAccessService.requireAdmin();
        McpCatalogSyncOutcome outcome = mcpCatalogSyncService.sync(requireServer(serverId));
        mcpRuntimeToolRegistry.invalidate();
        return SyncMcpToolCatalogResponse.builder()
                .success(outcome.success())
                .errorCode(outcome.errorCode())
                .errorMessage(outcome.errorMessage())
                .negotiatedProtocolVersion(outcome.discoveryResult() == null ? null : outcome.discoveryResult().negotiatedProtocolVersion())
                .remoteServerName(outcome.discoveryResult() == null ? null : outcome.discoveryResult().remoteServerName())
                .remoteServerVersion(outcome.discoveryResult() == null ? null : outcome.discoveryResult().remoteServerVersion())
                .createdCount(outcome.createdCount())
                .updatedCount(outcome.updatedCount())
                .staleCount(outcome.staleCount())
                .activeToolCount(outcome.discoveryResult() == null || outcome.discoveryResult().tools() == null ? 0 : outcome.discoveryResult().tools().size())
                .activeTools(outcome.discoveryResult() == null || outcome.discoveryResult().tools() == null
                        ? List.of()
                        : outcome.discoveryResult().tools().stream().map(tool -> toDiscoveredToolVO(tool, outcome.server())).toList())
                .syncedAt(outcome.server() == null ? null : outcome.server().getLastSyncAt())
                .server(outcome.server() == null ? null : toServerVO(outcome.server()))
                .build();
    }

    private McpServerDTO requireServer(String serverId) {
        McpServerDTO server = mcpServerRepository.findById(serverId);
        if (server == null) {
            throw new ClientException(BaseErrorCode.NOT_FOUND, "MCP server not found: " + serverId);
        }
        return server;
    }

    private void ensureSlugAvailable(String slug, String currentServerId) {
        McpServerDTO existing = mcpServerRepository.findBySlug(slug);
        if (existing != null && !existing.getId().equals(currentServerId)) {
            throw new ClientException(BaseErrorCode.CONFLICT, "MCP server slug already exists: " + slug);
        }
    }

    private void saveServerOrThrow(McpServerDTO server) {
        try {
            if (!mcpServerRepository.save(server)) {
                throw new BizException("Failed to create MCP server");
            }
        } catch (DataIntegrityViolationException e) {
            throw translatePersistenceException(e, server == null ? null : server.getSlug());
        }
    }

    private void updateServerOrThrow(McpServerDTO server) {
        try {
            if (!mcpServerRepository.update(server)) {
                throw new BizException("Failed to update MCP server: " + (server == null ? null : server.getId()));
            }
        } catch (DataIntegrityViolationException e) {
            throw translatePersistenceException(e, server == null ? null : server.getSlug());
        }
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

    private McpServerVO toServerVO(McpServerDTO dto) {
        return McpServerVO.builder()
                .id(dto.getId())
                .slug(dto.getSlug())
                .name(dto.getName())
                .description(dto.getDescription())
                .protocol(dto.getProtocol())
                .authType(dto.getAuthType())
                .endpointUrl(dto.getEndpointUrl())
                .status(dto.getStatus())
                .consecutiveFailures(dto.getConsecutiveFailures())
                .lastTestedAt(dto.getLastTestedAt())
                .lastInitializedAt(dto.getLastInitializedAt())
                .lastSyncAt(dto.getLastSyncAt())
                .lastErrorCode(dto.getLastErrorCode())
                .lastErrorMessage(dto.getLastErrorMessage())
                .unresolvedReferenceCount(resolveUnresolvedReferenceCount(dto))
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }

    private Integer resolveUnresolvedReferenceCount(McpServerDTO server) {
        if (server == null || server.getId() == null) {
            return 0;
        }
        List<String> toolNames = mcpToolCatalogRepository.findByServerId(server.getId()).stream()
                .map(McpToolCatalogDTO::getExposedModelName)
                .filter(StringUtils::hasText)
                .toList();
        return referenceInspector.inspect(toolNames).size();
    }

    private McpToolCatalogVO toCatalogVO(McpToolCatalogDTO dto) {
        return McpToolCatalogVO.builder()
                .id(dto.getId())
                .serverId(dto.getServerId())
                .remoteOriginalName(dto.getRemoteOriginalName())
                .toolDescription(dto.getToolDescription())
                .exposedModelName(dto.getExposedModelName())
                .status(dto.getStatus())
                .lastSyncedAt(dto.getLastSyncedAt())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }

    private McpToolReferenceVO toReferenceVO(McpToolReferenceDTO dto) {
        return new McpToolReferenceVO(
                dto.getReferenceType(),
                dto.getReferenceId(),
                dto.getReferenceName(),
                dto.getReferencePath()
        );
    }

    private McpDiscoveredToolVO toDiscoveredToolVO(McpRemoteToolDescriptor descriptor, McpServerDTO server) {
        return McpDiscoveredToolVO.builder()
                .remoteOriginalName(descriptor.remoteOriginalName())
                .exposedModelName(server == null ? null : toolNameNormalizer.normalizeToolName(server.getSlug(), descriptor.remoteOriginalName()))
                .toolDescription(descriptor.toolDescription())
                .schemaHash(descriptor.schemaHash())
                .build();
    }
}
