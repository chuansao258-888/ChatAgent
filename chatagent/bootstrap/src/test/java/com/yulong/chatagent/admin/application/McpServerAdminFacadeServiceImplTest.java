package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.CreateMcpServerRequest;
import com.yulong.chatagent.admin.model.request.UpdateMcpServerRequest;
import com.yulong.chatagent.admin.model.response.DeleteMcpServerResponse;
import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.admin.port.McpServerReferenceQueryRepository;
import com.yulong.chatagent.admin.port.McpToolCatalogRepository;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.mcp.application.McpCatalogSyncService;
import com.yulong.chatagent.mcp.application.McpServerTestService;
import com.yulong.chatagent.mcp.runtime.McpRuntimeToolRegistry;
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
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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

    private McpServerAdminFacadeServiceImpl facadeService;

    @BeforeEach
    void setUp() {
        facadeService = new McpServerAdminFacadeServiceImpl(
                adminAccessService,
                mcpServerRepository,
                mcpToolCatalogRepository,
                new McpEndpointValidator(new MockEnvironment()),
                new McpCredentialCipher(BASE64_KEY, "v1"),
                new McpServerStatusMachine(),
                new McpServerReferenceInspector(referenceQueryRepository),
                new McpToolNameNormalizer(),
                mcpServerTestService,
                mcpCatalogSyncService,
                mcpRuntimeToolRegistry,
                mcpAlertService
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
}
