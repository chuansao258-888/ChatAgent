package com.yulong.chatagent.mcp.application;

import com.yulong.chatagent.admin.application.McpFeatureFlag;
import com.yulong.chatagent.admin.application.McpToolNameNormalizer;
import com.yulong.chatagent.admin.port.McpToolCatalogRepository;
import com.yulong.chatagent.mcp.metrics.McpMetricsRecorder;
import com.yulong.chatagent.mcp.model.McpDiscoveryResult;
import com.yulong.chatagent.mcp.model.McpRemoteToolDescriptor;
import com.yulong.chatagent.mcp.model.McpSchemaDriftOutcome;
import com.yulong.chatagent.mcp.runtime.McpRuntimeToolRegistry;
import com.yulong.chatagent.mcp.transport.McpTransportClient;
import com.yulong.chatagent.mcp.transport.McpTransportException;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import com.yulong.chatagent.support.enums.McpServerStatus;
import com.yulong.chatagent.support.enums.McpToolCatalogStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpSchemaDriftDetectorTest {

    @Mock
    private McpTransportClient transportClient;

    @Mock
    private McpToolCatalogRepository toolCatalogRepository;

    @Mock
    private McpServerTestService serverTestService;

    @Mock
    private McpMetricsRecorder metricsRecorder;

    @Mock
    private McpRuntimeToolRegistry runtimeToolRegistry;

    private McpSchemaDriftDetector detector;
    private McpServerDTO server;

    @BeforeEach
    void setUp() {
        McpFeatureFlag featureFlag = new McpFeatureFlag();
        featureFlag.setEnabled(true);
        detector = new McpSchemaDriftDetector(
                featureFlag,
                transportClient,
                toolCatalogRepository,
                new McpToolNameNormalizer(),
                serverTestService,
                metricsRecorder,
                runtimeToolRegistry
        );
        server = McpServerDTO.builder()
                .id("srv-1")
                .slug("google")
                .name("Google")
                .protocol(McpProtocol.HTTP)
                .authType(McpAuthType.NONE)
                .status(McpServerStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void shouldMarkCatalogRowsStaleWhenRemoteSchemaChanges() {
        McpDiscoveryResult discoveryResult = new McpDiscoveryResult(
                "2025-06-18",
                "Stub MCP",
                "1.0.0",
                LocalDateTime.now(),
                List.of(new McpRemoteToolDescriptor("search", "Search tool", "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}", "hash-new"))
        );
        when(transportClient.discover(server)).thenReturn(discoveryResult);
        when(toolCatalogRepository.findByServerId("srv-1")).thenReturn(List.of(
                McpToolCatalogDTO.builder()
                        .id("tool-1")
                        .serverId("srv-1")
                        .remoteOriginalName("search")
                        .toolDescription("Old tool")
                        .exposedModelName("mcp_google_search")
                        .schemaJson("{\"type\":\"object\"}")
                        .schemaHash("hash-old")
                        .status(McpToolCatalogStatus.ENABLED)
                        .createdAt(LocalDateTime.now().minusDays(1))
                        .build()
        ));
        when(toolCatalogRepository.upsert(any(McpToolCatalogDTO.class))).thenReturn(true);
        when(toolCatalogRepository.markMissingAsStale(anyString(), any(), any(), any())).thenReturn(0);
        when(serverTestService.persistSchemaDriftDetected(any(), any(), any(), anyInt(), anyString())).thenReturn(server);

        McpSchemaDriftOutcome outcome = detector.detect(server);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.driftDetected()).isTrue();
        assertThat(outcome.staleToolCount()).isEqualTo(1);
        ArgumentCaptor<McpToolCatalogDTO> captor = ArgumentCaptor.forClass(McpToolCatalogDTO.class);
        verify(toolCatalogRepository).upsert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(McpToolCatalogStatus.STALE);
        verify(runtimeToolRegistry).invalidate();
        verify(metricsRecorder).recordSchemaDrift(server, 1);
    }

    @Test
    void shouldPersistConnectivityFailureWhenDriftCheckCannotReachServer() {
        when(transportClient.discover(server)).thenThrow(new McpTransportException("MCP_TIMEOUT", "timed out"));
        when(serverTestService.persistSyncFailure(any(), anyString(), anyString())).thenReturn(server);

        McpSchemaDriftOutcome outcome = detector.detect(server);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("MCP_TIMEOUT");
        verify(metricsRecorder).recordSchemaDriftFailure(server, "MCP_TIMEOUT");
        verify(runtimeToolRegistry).invalidate();
    }
}
