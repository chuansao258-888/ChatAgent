package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.CreateMcpServerRequest;
import com.yulong.chatagent.admin.model.request.UpdateMcpServerRequest;
import com.yulong.chatagent.admin.model.response.DeleteMcpServerResponse;
import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.admin.port.McpServerReferenceQueryRepository;
import com.yulong.chatagent.admin.port.McpToolCatalogRepository;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.exception.ClientException;
import com.yulong.chatagent.intent.application.IntentTreeCacheManager;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.IntentNodeLevel;
import com.yulong.chatagent.intent.model.IntentNodeStatus;
import com.yulong.chatagent.intent.port.IntentKnowledgeBaseRepository;
import com.yulong.chatagent.intent.port.IntentNodeRepository;
import com.yulong.chatagent.mcp.application.McpCatalogSyncService;
import com.yulong.chatagent.mcp.application.McpCredentialCipher;
import com.yulong.chatagent.mcp.application.McpServerReferenceInspector;
import com.yulong.chatagent.mcp.application.McpServerStatusMachine;
import com.yulong.chatagent.mcp.application.McpServerTestService;
import com.yulong.chatagent.mcp.application.McpToolNameNormalizer;
import com.yulong.chatagent.mcp.runtime.McpRuntimeToolRegistry;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import com.yulong.chatagent.support.dto.McpToolReferenceDTO;
import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import com.yulong.chatagent.support.enums.McpReferenceType;
import com.yulong.chatagent.support.enums.McpServerStatus;
import com.yulong.chatagent.support.enums.McpToolCatalogStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpServerAdminFacadeServiceImplTest {

    private static final String BASE64_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private McpServerRepository mcpServerRepository;

    @Mock
    private McpToolCatalogRepository mcpToolCatalogRepository;

    @Mock
    private McpServerReferenceQueryRepository referenceQueryRepository;

    @Mock
    private McpServerTestService mcpServerTestService;

    @Mock
    private McpCatalogSyncService mcpCatalogSyncService;

    @Mock
    private McpRuntimeToolRegistry mcpRuntimeToolRegistry;

    @Mock
    private McpAlertService mcpAlertService;

    @Mock
    private IntentNodeRepository intentNodeRepository;

    @Mock
    private IntentKnowledgeBaseRepository intentKnowledgeBaseRepository;

    @Mock
    private IntentTreeCacheManager intentTreeCacheManager;

    private McpServerAdminFacadeServiceImpl facadeService;

    @BeforeEach
    void setUp() {
        McpServerReferenceInspector referenceInspector = new McpServerReferenceInspector(referenceQueryRepository);
        McpServerCrudHelper crudHelper = new McpServerCrudHelper(
                new McpEndpointValidator(new MockEnvironment()),
                new McpCredentialCipher(BASE64_KEY, "v1"),
                new McpServerStatusMachine(),
                mcpServerRepository
        );
        McpServerDeleteHandler deleteHandler = new McpServerDeleteHandler(
                mcpToolCatalogRepository,
                mcpServerRepository,
                referenceInspector,
                mcpAlertService,
                intentNodeRepository,
                intentKnowledgeBaseRepository,
                intentTreeCacheManager,
                mcpRuntimeToolRegistry
        );
        facadeService = new McpServerAdminFacadeServiceImpl(
                adminAccessService,
                mcpServerRepository,
                mcpToolCatalogRepository,
                referenceInspector,
                new McpToolNameNormalizer(),
                mcpServerTestService,
                mcpCatalogSyncService,
                mcpRuntimeToolRegistry,
                crudHelper,
                deleteHandler
        );
        when(adminAccessService.requireAdmin()).thenReturn(LoginUser.builder()
                .userId("admin-1")
                .role("admin")
                .build());
    }

    @Test
    void shouldCreateDisabledServerByDefault() {
        when(mcpServerRepository.findBySlug("google_search")).thenReturn(null);
        when(mcpServerRepository.save(any(McpServerDTO.class))).thenReturn(true);

        CreateMcpServerRequest request = new CreateMcpServerRequest();
        request.setSlug("google_search");
        request.setName("Google Search");
        request.setProtocol("HTTP");
        request.setAuthType("BEARER_TOKEN");
        request.setEndpointUrl("https://example.com/mcp");
        request.setCredentials("secret");

        facadeService.createServer(request);

        ArgumentCaptor<McpServerDTO> captor = ArgumentCaptor.forClass(McpServerDTO.class);
        verify(mcpServerRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(McpServerStatus.DISABLED);
        assertThat(captor.getValue().getEncryptedCredentials()).isNotBlank();
    }

    @Test
    void shouldTranslateDuplicateSlugConstraintToConflictOnCreate() {
        when(mcpServerRepository.findBySlug("weather")).thenReturn(null);
        when(mcpServerRepository.save(any(McpServerDTO.class)))
                .thenThrow(new DuplicateKeyException("ERROR: duplicate key value violates unique constraint \"uq_t_mcp_server_slug\""));

        CreateMcpServerRequest request = new CreateMcpServerRequest();
        request.setSlug("weather");
        request.setName("Weather");
        request.setProtocol("HTTP");
        request.setAuthType("NONE");
        request.setEndpointUrl("https://example.com/mcp");

        assertThatThrownBy(() -> facadeService.createServer(request))
                .isInstanceOf(ClientException.class)
                .satisfies(error -> {
                    ClientException exception = (ClientException) error;
                    assertThat(exception.getErrorCode()).isEqualTo(BaseErrorCode.CONFLICT);
                    assertThat(exception.getErrorMessage()).isEqualTo("MCP server slug already exists: weather");
                });
    }

    @Test
    void shouldMarkSensitiveUpdatesAsStale() {
        when(mcpServerRepository.findById("srv-1")).thenReturn(existingServer());
        when(mcpServerRepository.findBySlug("google_search")).thenReturn(existingServer());
        when(mcpServerRepository.update(any(McpServerDTO.class))).thenReturn(true);

        UpdateMcpServerRequest request = new UpdateMcpServerRequest();
        request.setEndpointUrl("https://example.com/updated");

        facadeService.updateServer("srv-1", request);

        ArgumentCaptor<McpServerDTO> captor = ArgumentCaptor.forClass(McpServerDTO.class);
        verify(mcpServerRepository).update(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(McpServerStatus.STALE);
        assertThat(captor.getValue().getEndpointUrl()).isEqualTo("https://example.com/updated");
    }

    @Test
    void shouldTranslateDuplicateSlugConstraintToConflictOnUpdate() {
        McpServerDTO existing = existingServer();
        when(mcpServerRepository.findById("srv-1")).thenReturn(existing);
        when(mcpServerRepository.findBySlug("weather")).thenReturn(null);
        when(mcpServerRepository.update(any(McpServerDTO.class)))
                .thenThrow(new DuplicateKeyException("ERROR: duplicate key value violates unique constraint \"uq_t_mcp_server_slug\""));

        UpdateMcpServerRequest request = new UpdateMcpServerRequest();
        request.setSlug("weather");

        assertThatThrownBy(() -> facadeService.updateServer("srv-1", request))
                .isInstanceOf(ClientException.class)
                .satisfies(error -> {
                    ClientException exception = (ClientException) error;
                    assertThat(exception.getErrorCode()).isEqualTo(BaseErrorCode.CONFLICT);
                    assertThat(exception.getErrorMessage()).isEqualTo("MCP server slug already exists: weather");
                });
    }

    @Test
    void shouldBlockDeleteWhenReferencesExistAndForceIsFalse() {
        when(mcpServerRepository.findById("srv-1")).thenReturn(existingServer());
        when(mcpToolCatalogRepository.findByServerId("srv-1")).thenReturn(List.of(
                McpToolCatalogDTO.builder()
                        .id("tool-1")
                        .serverId("srv-1")
                        .exposedModelName("mcp_google_search")
                        .status(McpToolCatalogStatus.ENABLED)
                        .build()
        ));
        when(referenceQueryRepository.findActiveReferencesByToolNames(List.of("mcp_google_search"))).thenReturn(List.of(
                new McpToolReferenceDTO(McpReferenceType.AGENT, "agent-1", "Support Agent", "/allowedTools")
        ));

        DeleteMcpServerResponse response = facadeService.deleteServer("srv-1", false);

        assertThat(response.isDeleted()).isFalse();
        assertThat(response.getActiveReferenceCount()).isEqualTo(1);
        verify(mcpServerRepository, never()).softDelete(anyString(), any(), any());
    }

    @Test
    void shouldRaiseAlertWhenForceDeletingServerWithActiveReferences() {
        when(mcpServerRepository.findById("srv-1")).thenReturn(existingServer());
        when(mcpToolCatalogRepository.findByServerId("srv-1")).thenReturn(List.of(
                McpToolCatalogDTO.builder()
                        .id("tool-1")
                        .serverId("srv-1")
                        .exposedModelName("mcp_google_search")
                        .status(McpToolCatalogStatus.ENABLED)
                        .build()
        ));
        when(referenceQueryRepository.findActiveReferencesByToolNames(List.of("mcp_google_search"))).thenReturn(List.of(
                new McpToolReferenceDTO(McpReferenceType.AGENT, "agent-1", "Support Agent", "/allowedTools")
        ));
        when(mcpServerRepository.softDelete(anyString(), any(), any())).thenReturn(true);
        when(mcpToolCatalogRepository.softDeleteByServerId(anyString(), any(), any())).thenReturn(true);

        DeleteMcpServerResponse response = facadeService.deleteServer("srv-1", true);

        assertThat(response.isDeleted()).isTrue();
        verify(mcpAlertService).raiseUnresolvedReference("srv-1", "google_search", 1, List.of("/allowedTools"));
    }

    @Test
    void shouldDeleteCatalogRowsBeforeRemovingServerRecord() {
        when(mcpServerRepository.findById("srv-1")).thenReturn(existingServer());
        when(mcpToolCatalogRepository.findByServerId("srv-1")).thenReturn(List.of(enabledToolCatalog()));
        when(referenceQueryRepository.findActiveReferencesByToolNames(List.of("mcp_google_search"))).thenReturn(List.of());
        when(mcpToolCatalogRepository.softDeleteByServerId(anyString(), any(), any())).thenReturn(true);
        when(mcpServerRepository.softDelete(anyString(), any(), any())).thenReturn(true);

        DeleteMcpServerResponse response = facadeService.deleteServer("srv-1", false);

        assertThat(response.isDeleted()).isTrue();
        org.mockito.InOrder inOrder = inOrder(mcpToolCatalogRepository, mcpServerRepository);
        inOrder.verify(mcpToolCatalogRepository).softDeleteByServerId(anyString(), any(), any());
        inOrder.verify(mcpServerRepository).softDelete(anyString(), any(), any());
    }

    @Test
    void shouldDeleteIntentToolNodeWhenForceDeleteRemovesItsOnlyBoundTool() {
        when(mcpServerRepository.findById("srv-1")).thenReturn(existingServer());
        when(mcpToolCatalogRepository.findByServerId("srv-1")).thenReturn(List.of(enabledToolCatalog()));

        List<McpToolReferenceDTO> initialReferences = List.of(
                new McpToolReferenceDTO(McpReferenceType.INTENT_NODE, "intent-1", "Weather Intent", "/allowedTools")
        );
        when(referenceQueryRepository.findActiveReferencesByToolNames(List.of("mcp_google_search")))
                .thenReturn(initialReferences)
                .thenReturn(List.of());
        when(intentNodeRepository.findById("intent-1")).thenReturn(toolIntentNode("intent-1", List.of("mcp_google_search")));
        when(intentNodeRepository.deleteByIds(List.of("intent-1"))).thenReturn(true);
        when(mcpServerRepository.softDelete(anyString(), any(), any())).thenReturn(true);
        when(mcpToolCatalogRepository.softDeleteByServerId(anyString(), any(), any())).thenReturn(true);

        DeleteMcpServerResponse response = facadeService.deleteServer("srv-1", true);

        assertThat(response.isDeleted()).isTrue();
        assertThat(response.getActiveReferenceCount()).isEqualTo(1);
        assertThat(response.getUnresolvedReferenceCount()).isZero();
        verify(intentKnowledgeBaseRepository).deleteByIntentNodeIds(List.of("intent-1"));
        verify(intentNodeRepository).deleteByIds(List.of("intent-1"));
        verify(intentTreeCacheManager).refreshActiveSnapshot("agent-1");
        verify(mcpAlertService, never()).raiseUnresolvedReference(anyString(), anyString(), any(Integer.class), any());
    }

    @Test
    void shouldTrimDeletedToolFromIntentNodeWhenOtherToolBindingsRemain() {
        when(mcpServerRepository.findById("srv-1")).thenReturn(existingServer());
        when(mcpToolCatalogRepository.findByServerId("srv-1")).thenReturn(List.of(enabledToolCatalog()));

        List<McpToolReferenceDTO> initialReferences = List.of(
                new McpToolReferenceDTO(McpReferenceType.INTENT_NODE, "intent-2", "Multi Tool Intent", "/allowedTools")
        );
        when(referenceQueryRepository.findActiveReferencesByToolNames(List.of("mcp_google_search")))
                .thenReturn(initialReferences)
                .thenReturn(List.of());
        when(intentNodeRepository.findById("intent-2")).thenReturn(toolIntentNode("intent-2", List.of("mcp_google_search", "mcp_bing_search")));
        when(intentNodeRepository.update(any(IntentNodeDTO.class))).thenReturn(true);
        when(mcpServerRepository.softDelete(anyString(), any(), any())).thenReturn(true);
        when(mcpToolCatalogRepository.softDeleteByServerId(anyString(), any(), any())).thenReturn(true);

        DeleteMcpServerResponse response = facadeService.deleteServer("srv-1", true);

        assertThat(response.isDeleted()).isTrue();
        ArgumentCaptor<IntentNodeDTO> intentNodeCaptor = ArgumentCaptor.forClass(IntentNodeDTO.class);
        verify(intentNodeRepository).update(intentNodeCaptor.capture());
        assertThat(intentNodeCaptor.getValue().getAllowedTools()).containsExactly("mcp_bing_search");
        verify(intentNodeRepository, never()).deleteByIds(List.of("intent-2"));
        verify(intentTreeCacheManager).refreshActiveSnapshot("agent-1");
        verify(mcpAlertService, never()).raiseUnresolvedReference(anyString(), anyString(), any(Integer.class), any());
    }

    private McpServerDTO existingServer() {
        return McpServerDTO.builder()
                .id("srv-1")
                .slug("google_search")
                .name("Google Search")
                .description("Search MCP")
                .protocol(McpProtocol.HTTP)
                .authType(McpAuthType.BEARER_TOKEN)
                .endpointUrl("https://example.com/mcp")
                .status(McpServerStatus.DISABLED)
                .consecutiveFailures(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private McpToolCatalogDTO enabledToolCatalog() {
        return McpToolCatalogDTO.builder()
                .id("tool-1")
                .serverId("srv-1")
                .exposedModelName("mcp_google_search")
                .status(McpToolCatalogStatus.ENABLED)
                .build();
    }

    private IntentNodeDTO toolIntentNode(String id, List<String> allowedTools) {
        return IntentNodeDTO.builder()
                .id(id)
                .agentId("agent-1")
                .version(0)
                .status(IntentNodeStatus.DRAFT)
                .nodeLevel(IntentNodeLevel.TOPIC)
                .name("Tool intent")
                .intentKind(IntentKind.TOOL)
                .allowedTools(new ArrayList<>(allowedTools))
                .enabled(true)
                .sortOrder(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
