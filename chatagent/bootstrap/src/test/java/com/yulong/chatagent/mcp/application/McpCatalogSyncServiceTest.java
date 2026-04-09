package com.yulong.chatagent.mcp.application;

import com.yulong.chatagent.admin.application.McpFeatureFlag;
import com.yulong.chatagent.admin.application.McpAlertService;
import com.yulong.chatagent.admin.application.McpToolNameNormalizer;
import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.admin.port.McpToolCatalogRepository;
import com.yulong.chatagent.mcp.model.McpCatalogSyncOutcome;
import com.yulong.chatagent.mcp.model.McpDiscoveryResult;
import com.yulong.chatagent.mcp.model.McpRemoteToolDescriptor;
import com.yulong.chatagent.mcp.transport.McpTransportClient;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import com.yulong.chatagent.support.enums.McpServerStatus;
import com.yulong.chatagent.support.enums.McpToolCatalogStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpCatalogSyncServiceTest {

    @Mock
    private McpTransportClient transportClient;

    @Mock
    private McpToolCatalogRepository toolCatalogRepository;

    @Mock
    private McpServerRepository serverRepository;

    @Mock
    private McpAlertService alertService;

    private McpCatalogSyncService syncService;

    @BeforeEach
    void setUp() {
        McpFeatureFlag featureFlag = new McpFeatureFlag();
        featureFlag.setEnabled(true);
        McpServerTestService serverTestService = new McpServerTestService(
                featureFlag,
                transportClient,
                serverRepository,
                new com.yulong.chatagent.admin.application.McpServerStatusMachine(),
                alertService
        );
        syncService = new McpCatalogSyncService(
                featureFlag,
                transportClient,
                toolCatalogRepository,
                new McpToolNameNormalizer(),
                serverTestService
        );
        when(serverRepository.update(any(McpServerDTO.class))).thenReturn(true);
        when(toolCatalogRepository.upsert(any(McpToolCatalogDTO.class))).thenReturn(true);
    }

    @Test
    void shouldUpsertDiscoveredToolsAndMarkMissingRowsStale() {
        McpServerDTO server = existingServer();
        when(transportClient.discover(server)).thenReturn(new McpDiscoveryResult(
                "2025-06-18",
                "Stub MCP",
                "1.0.0",
                LocalDateTime.now(),
                List.of(new McpRemoteToolDescriptor("search", "Search tool", "{\"type\":\"object\"}", "hash-1"))
        ));
        when(toolCatalogRepository.findByServerId("srv-1")).thenReturn(List.of(
                McpToolCatalogDTO.builder()
                        .id("tool-1")
                        .serverId("srv-1")
                        .remoteOriginalName("search")
                        .toolDescription("Old tool")
                        .exposedModelName("mcp_google_search")
                        .schemaJson("{\"type\":\"object\"}")
                        .schemaHash("old-hash")
                        .status(McpToolCatalogStatus.STALE)
                        .createdAt(LocalDateTime.now().minusDays(1))
                        .build()
        ));
        when(toolCatalogRepository.markMissingAsStale(any(), anyList(), any(), any())).thenReturn(1);

        McpCatalogSyncOutcome outcome = syncService.sync(server);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.updatedCount()).isEqualTo(1);
        assertThat(outcome.staleCount()).isEqualTo(1);
        assertThat(outcome.server().getStatus()).isEqualTo(McpServerStatus.ACTIVE);
        verify(toolCatalogRepository).upsert(any(McpToolCatalogDTO.class));
    }

    private McpServerDTO existingServer() {
        return McpServerDTO.builder()
                .id("srv-1")
                .slug("google")
                .name("Google")
                .protocol(McpProtocol.HTTP)
                .authType(McpAuthType.NONE)
                .endpointUrl("https://example.com/mcp")
                .status(McpServerStatus.STALE)
                .consecutiveFailures(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
