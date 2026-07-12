package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.UpsertMcpServerRequest;
import com.yulong.chatagent.admin.model.response.DeleteMcpServerResponse;
import com.yulong.chatagent.admin.model.response.GetMcpServerResponse;
import com.yulong.chatagent.admin.model.response.SyncMcpToolCatalogResponse;
import com.yulong.chatagent.admin.model.response.TestMcpServerResponse;
import com.yulong.chatagent.admin.model.vo.McpDiscoveredToolVO;
import com.yulong.chatagent.admin.model.vo.McpServerVO;
import com.yulong.chatagent.admin.model.vo.McpToolCatalogVO;
import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.admin.port.McpToolCatalogRepository;
import com.yulong.chatagent.mcp.application.McpCatalogSyncService;
import com.yulong.chatagent.mcp.application.McpFeatureFlag;
import com.yulong.chatagent.mcp.application.McpServerReferenceInspector;
import com.yulong.chatagent.mcp.application.McpServerTestService;
import com.yulong.chatagent.mcp.application.McpToolNameNormalizer;
import com.yulong.chatagent.mcp.model.McpCatalogSyncOutcome;
import com.yulong.chatagent.mcp.model.McpRemoteToolDescriptor;
import com.yulong.chatagent.mcp.model.McpServerProbeOutcome;
import com.yulong.chatagent.mcp.runtime.McpRuntimeToolRegistry;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import com.yulong.chatagent.support.dto.McpToolReferenceDTO;
import com.yulong.chatagent.support.enums.McpServerStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.time.LocalDateTime;

/**
 * Phase 1a MCP admin CRUD plus delete-time reference checks.
 */
@Service
public class McpServerAdminFacadeServiceImpl implements McpServerAdminFacadeService {

    private final AdminAccessService adminAccessService;
    private final McpServerRepository mcpServerRepository;
    private final McpToolCatalogRepository mcpToolCatalogRepository;
    private final McpServerReferenceInspector referenceInspector;
    private final McpToolNameNormalizer toolNameNormalizer;
    private final McpServerTestService mcpServerTestService;
    private final McpCatalogSyncService mcpCatalogSyncService;
    private final McpRuntimeToolRegistry mcpRuntimeToolRegistry;
    private final McpServerCrudHelper crudHelper;
    private final McpServerDeleteHandler deleteHandler;

    public McpServerAdminFacadeServiceImpl(AdminAccessService adminAccessService,
                                           McpServerRepository mcpServerRepository,
                                           McpToolCatalogRepository mcpToolCatalogRepository,
                                           McpServerReferenceInspector referenceInspector,
                                           McpToolNameNormalizer toolNameNormalizer,
                                           McpServerTestService mcpServerTestService,
                                           McpCatalogSyncService mcpCatalogSyncService,
                                           McpRuntimeToolRegistry mcpRuntimeToolRegistry,
                                           McpServerCrudHelper crudHelper,
                                           McpServerDeleteHandler deleteHandler) {
        this.adminAccessService = adminAccessService;
        this.mcpServerRepository = mcpServerRepository;
        this.mcpToolCatalogRepository = mcpToolCatalogRepository;
        this.referenceInspector = referenceInspector;
        this.toolNameNormalizer = toolNameNormalizer;
        this.mcpServerTestService = mcpServerTestService;
        this.mcpCatalogSyncService = mcpCatalogSyncService;
        this.mcpRuntimeToolRegistry = mcpRuntimeToolRegistry;
        this.crudHelper = crudHelper;
        this.deleteHandler = deleteHandler;
    }

    @Override
    public List<McpServerVO> getServers() {
        adminAccessService.requireAdmin();
        return mcpServerRepository.findAll().stream()
                .map(this::toServerVO)
                .toList();
    }

    @Override
    public GetMcpServerResponse getServer(String serverId) {
        adminAccessService.requireAdmin();
        McpServerDTO server = crudHelper.requireServer(serverId);
        List<McpToolCatalogVO> catalog = mcpToolCatalogRepository.findByServerId(serverId).stream()
                .map(this::toCatalogVO)
                .toList();
        return new GetMcpServerResponse(toServerVO(server), catalog);
    }

    @Override
    @Transactional
    public String createServer(UpsertMcpServerRequest request) {
        adminAccessService.requireAdmin();
        McpServerDTO server = crudHelper.buildCreateDTO(request);
        crudHelper.ensureSlugAvailable(server.getSlug(), null);
        crudHelper.saveServerOrThrow(server);
        mcpRuntimeToolRegistry.invalidate();
        return server.getId();
    }

    @Override
    @Transactional
    public void updateServer(String serverId, UpsertMcpServerRequest request) {
        adminAccessService.requireAdmin();
        McpServerDTO existing = crudHelper.requireServer(serverId);
        McpServerDTO updated = crudHelper.buildUpdateDTO(existing, request);
        crudHelper.ensureSlugAvailable(updated.getSlug(), existing.getId());
        crudHelper.updateServerOrThrow(updated);
        mcpRuntimeToolRegistry.invalidate();
    }

    @Override
    @Transactional
    public DeleteMcpServerResponse deleteServer(String serverId, boolean force) {
        adminAccessService.requireAdmin();
        McpServerDTO server = crudHelper.requireServer(serverId);
        return deleteHandler.execute(server, force);
    }

    @Override
    public TestMcpServerResponse testServer(String serverId) {
        adminAccessService.requireAdmin();
        McpServerProbeOutcome outcome = mcpServerTestService.test(crudHelper.requireServer(serverId));
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
        McpCatalogSyncOutcome outcome = mcpCatalogSyncService.sync(crudHelper.requireServer(serverId));
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

    @Override
    @Transactional
    public void updateToolEffectPolicy(String toolId, String effectPolicy, long expectedPolicyVersion) {
        adminAccessService.requireAdmin();
        com.yulong.chatagent.agent.tools.ToolEffectClass parsed;
        try {
            parsed = com.yulong.chatagent.agent.tools.ToolEffectClass.valueOf(effectPolicy);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid MCP effect policy");
        }
        if (!mcpToolCatalogRepository.updateEffectPolicy(
                toolId, parsed.name(), expectedPolicyVersion, LocalDateTime.now())) {
            throw new IllegalStateException("MCP effect policy update conflict");
        }
        mcpRuntimeToolRegistry.invalidate();
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
                .effectPolicy(dto.getEffectPolicy())
                .policyVersion(dto.getPolicyVersion())
                .lastSyncedAt(dto.getLastSyncedAt())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
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
