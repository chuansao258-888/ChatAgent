package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.admin.port.McpToolCatalogRepository;
import com.yulong.chatagent.mcp.application.McpFeatureFlag;
import com.yulong.chatagent.mcp.application.McpServerReferenceInspector;
import com.yulong.chatagent.mcp.metrics.McpMetricsRecorder;
import com.yulong.chatagent.mcp.runtime.McpRolloutPolicy;
import com.yulong.chatagent.mcp.runtime.McpRolloutProperties;
import com.yulong.chatagent.mcp.runtime.McpRuntimeProtectionProperties;
import com.yulong.chatagent.mcp.runtime.McpServerCircuitBreaker;
import com.yulong.chatagent.support.dto.McpAlertEventDTO;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import com.yulong.chatagent.support.dto.McpToolReferenceDTO;
import com.yulong.chatagent.support.enums.McpAlertSeverity;
import com.yulong.chatagent.support.enums.McpAlertStatus;
import com.yulong.chatagent.support.enums.McpAlertType;
import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import com.yulong.chatagent.support.enums.McpReferenceType;
import com.yulong.chatagent.support.enums.McpServerStatus;
import com.yulong.chatagent.support.enums.McpToolCatalogStatus;
import com.yulong.chatagent.support.persistence.mapper.DashboardMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardMcpFacadeServiceTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private DashboardMapper dashboardMapper;

    @Mock
    private McpServerRepository mcpServerRepository;

    @Mock
    private McpToolCatalogRepository mcpToolCatalogRepository;

    @Mock
    private McpServerReferenceInspector referenceInspector;

    @Mock
    private McpAlertService mcpAlertService;

    private DashboardFacadeServiceImpl facadeService;
    private McpMetricsRecorder metricsRecorder;

    @BeforeEach
    void setUp() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        metricsRecorder = new McpMetricsRecorder(beanFactory.getBeanProvider(MeterRegistry.class));
        McpFeatureFlag featureFlag = new McpFeatureFlag();
        featureFlag.setEnabled(true);
        McpRolloutProperties rolloutProperties = new McpRolloutProperties();
        rolloutProperties.setMode("AGENT_ALLOWLIST");
        rolloutProperties.setAllowedAgentIds(List.of("assistant-1"));
        DashboardMcpMetricsComposer mcpMetricsComposer = new DashboardMcpMetricsComposer(
                featureFlag,
                mcpServerRepository,
                mcpToolCatalogRepository,
                referenceInspector,
                metricsRecorder,
                mcpAlertService,
                new McpRolloutPolicy(rolloutProperties)
        );
        DashboardOverviewAggregator overviewAggregator = new DashboardOverviewAggregator(dashboardMapper);
        facadeService = new DashboardFacadeServiceImpl(
                adminAccessService,
                dashboardMapper,
                mcpMetricsComposer,
                overviewAggregator
        );
    }

    @Test
    void shouldAppendMcpSnapshotIntoPerformancePayload() {
        Map<String, Object> aggregate = new HashMap<>();
        aggregate.put("totalCount", 10L);
        aggregate.put("successCount", 8L);
        aggregate.put("errorCount", 2L);
        aggregate.put("slowCount", 1L);
        aggregate.put("noKnowledgeCount", 1L);
        aggregate.put("avgLatencyMs", 1200.0d);
        when(dashboardMapper.selectPerformanceAggregate(any(), any())).thenReturn(aggregate);
        when(dashboardMapper.selectSuccessfulDurations(any(), any())).thenReturn(List.of(100L, 200L, 300L));
        McpServerDTO server = McpServerDTO.builder()
                .id("srv-1")
                .slug("google")
                .name("Google Search")
                .protocol(McpProtocol.HTTP)
                .authType(McpAuthType.NONE)
                .status(McpServerStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        when(mcpServerRepository.findAll()).thenReturn(List.of(server));
        when(mcpToolCatalogRepository.findByServerId("srv-1")).thenReturn(List.of(
                McpToolCatalogDTO.builder()
                        .id("tool-1")
                        .serverId("srv-1")
                        .exposedModelName("mcp_google_search")
                        .status(McpToolCatalogStatus.ENABLED)
                        .build()
        ));
        when(referenceInspector.inspect(List.of("mcp_google_search"))).thenReturn(List.of(
                new McpToolReferenceDTO(McpReferenceType.AGENT, "agent-1", "Support Agent", "/allowedTools")
        ));
        when(mcpAlertService.openAlertCount()).thenReturn(2L);

        metricsRecorder.recordSuccess(server, "mcp_google_search", 100);
        metricsRecorder.recordFailure(server, "mcp_google_search", "MCP_TIMEOUT", 300);
        metricsRecorder.registerCircuitBreaker(server, new McpServerCircuitBreaker(new McpRuntimeProtectionProperties()));

        var performance = facadeService.getPerformance("24h");

        assertThat(performance.getMcp()).isNotNull();
        assertThat(performance.getMcp().getOpenAlertCount()).isEqualTo(2L);
        assertThat(performance.getMcp().getRolloutMode()).isEqualTo("AGENT_ALLOWLIST");
        assertThat(performance.getMcp().getServers()).hasSize(1);
        assertThat(performance.getMcp().getServers().get(0).getServerSlug()).isEqualTo("google");
        assertThat(performance.getMcp().getServers().get(0).getTotalCalls()).isEqualTo(2L);
        assertThat(performance.getMcp().getServers().get(0).getUnresolvedReferenceCount()).isEqualTo(1);
    }

    @Test
    void shouldExposeRecentMcpAlerts() {
        when(mcpAlertService.openAlertCount()).thenReturn(1L);
        when(mcpAlertService.recentOpenAlerts(10)).thenReturn(List.of(
                McpAlertEventDTO.builder()
                        .id("alert-1")
                        .serverId("srv-1")
                        .serverSlug("google")
                        .alertType(McpAlertType.SERVER_FAILED)
                        .severity(McpAlertSeverity.ERROR)
                        .status(McpAlertStatus.OPEN)
                        .summary("server failed")
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build()
        ));

        var alerts = facadeService.getMcpAlerts(10);

        assertThat(alerts.getOpenAlertCount()).isEqualTo(1L);
        assertThat(alerts.getAlerts()).hasSize(1);
        assertThat(alerts.getAlerts().get(0).getAlertType()).isEqualTo(McpAlertType.SERVER_FAILED);
    }
}
