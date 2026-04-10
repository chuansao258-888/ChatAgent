package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.vo.DashboardMcpAlertVO;
import com.yulong.chatagent.admin.model.vo.DashboardMcpAlertsVO;
import com.yulong.chatagent.admin.model.vo.DashboardMcpPerformanceVO;
import com.yulong.chatagent.admin.model.vo.DashboardMcpServerMetricVO;
import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.admin.port.McpToolCatalogRepository;
import com.yulong.chatagent.admin.application.McpAlertService;
import com.yulong.chatagent.mcp.application.McpFeatureFlag;
import com.yulong.chatagent.mcp.application.McpServerReferenceInspector;
import com.yulong.chatagent.mcp.metrics.McpMetricsRecorder;
import com.yulong.chatagent.mcp.metrics.McpServerMetricsSnapshot;
import com.yulong.chatagent.mcp.runtime.McpRolloutPolicy;
import com.yulong.chatagent.support.dto.McpAlertEventDTO;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;

/**
 * Composes MCP-related dashboard metrics and alerts.
 */
@Component
class DashboardMcpMetricsComposer {

    private final McpFeatureFlag mcpFeatureFlag;
    private final McpServerRepository mcpServerRepository;
    private final McpToolCatalogRepository mcpToolCatalogRepository;
    private final McpServerReferenceInspector referenceInspector;
    private final McpMetricsRecorder mcpMetricsRecorder;
    private final McpAlertService mcpAlertService;
    private final McpRolloutPolicy mcpRolloutPolicy;

    DashboardMcpMetricsComposer(McpFeatureFlag mcpFeatureFlag,
                                McpServerRepository mcpServerRepository,
                                McpToolCatalogRepository mcpToolCatalogRepository,
                                McpServerReferenceInspector referenceInspector,
                                McpMetricsRecorder mcpMetricsRecorder,
                                McpAlertService mcpAlertService,
                                McpRolloutPolicy mcpRolloutPolicy) {
        this.mcpFeatureFlag = mcpFeatureFlag;
        this.mcpServerRepository = mcpServerRepository;
        this.mcpToolCatalogRepository = mcpToolCatalogRepository;
        this.referenceInspector = referenceInspector;
        this.mcpMetricsRecorder = mcpMetricsRecorder;
        this.mcpAlertService = mcpAlertService;
        this.mcpRolloutPolicy = mcpRolloutPolicy;
    }

    public DashboardMcpPerformanceVO buildMcpPerformance() {
        if (mcpServerRepository == null) {
            return DashboardMcpPerformanceVO.builder()
                    .enabled(mcpFeatureFlag == null || mcpFeatureFlag.isEnabled())
                    .rolloutMode(mcpRolloutPolicy == null ? "ALL" : mcpRolloutPolicy.mode().name())
                    .allowedAgentCount(mcpRolloutPolicy == null ? 0 : mcpRolloutPolicy.allowedAgentIds().size())
                    .openAlertCount(mcpAlertService == null ? 0L : mcpAlertService.openAlertCount())
                    .serverCount(0)
                    .servers(List.of())
                    .build();
        }
        List<McpServerDTO> servers = mcpServerRepository.findAll().stream()
                .sorted(Comparator.comparing(McpServerDTO::getSlug, Comparator.nullsLast(String::compareTo)))
                .toList();
        return DashboardMcpPerformanceVO.builder()
                .enabled(mcpFeatureFlag == null || mcpFeatureFlag.isEnabled())
                .rolloutMode(mcpRolloutPolicy == null ? "ALL" : mcpRolloutPolicy.mode().name())
                .allowedAgentCount(mcpRolloutPolicy == null ? 0 : mcpRolloutPolicy.allowedAgentIds().size())
                .openAlertCount(mcpAlertService == null ? 0L : mcpAlertService.openAlertCount())
                .serverCount(servers.size())
                .servers(servers.stream().map(this::toMcpServerMetricVO).toList())
                .build();
    }

    public DashboardMcpAlertsVO getMcpAlerts(int limit) {
        if (mcpAlertService == null) {
            return DashboardMcpAlertsVO.builder()
                    .openAlertCount(0L)
                    .alerts(List.of())
                    .build();
        }
        int safeLimit = Math.max(1, limit);
        return DashboardMcpAlertsVO.builder()
                .openAlertCount(mcpAlertService.openAlertCount())
                .alerts(mcpAlertService.recentOpenAlerts(safeLimit).stream()
                        .map(this::toAlertVO)
                        .toList())
                .build();
    }

    private DashboardMcpServerMetricVO toMcpServerMetricVO(McpServerDTO server) {
        McpServerMetricsSnapshot snapshot = mcpMetricsRecorder == null
                ? McpServerMetricsSnapshot.builder()
                .serverId(server == null ? null : server.getId())
                .totalCalls(0L)
                .successCount(0L)
                .failureCount(0L)
                .rateLimitedCount(0L)
                .avgLatencyMs(0L)
                .qps(0.0d)
                .errorRate(0.0d)
                .circuitState(0)
                .build()
                : mcpMetricsRecorder.snapshot(server);
        return DashboardMcpServerMetricVO.builder()
                .serverId(server.getId())
                .serverSlug(server.getSlug())
                .serverName(server.getName())
                .status(server.getStatus())
                .unresolvedReferenceCount(resolveUnresolvedReferenceCount(server))
                .totalCalls(snapshot.totalCalls())
                .successCount(snapshot.successCount())
                .failureCount(snapshot.failureCount())
                .rateLimitedCount(snapshot.rateLimitedCount())
                .avgLatencyMs(snapshot.avgLatencyMs())
                .qps(snapshot.qps())
                .errorRate(snapshot.errorRate())
                .circuitState(snapshot.circuitState())
                .lastErrorCode(server.getLastErrorCode())
                .lastTestedAt(server.getLastTestedAt())
                .lastSyncAt(server.getLastSyncAt())
                .build();
    }

    private int resolveUnresolvedReferenceCount(McpServerDTO server) {
        if (server == null || server.getId() == null || mcpToolCatalogRepository == null || referenceInspector == null) {
            return 0;
        }
        List<String> toolNames = mcpToolCatalogRepository.findByServerId(server.getId()).stream()
                .map(McpToolCatalogDTO::getExposedModelName)
                .filter(StringUtils::hasText)
                .toList();
        return referenceInspector.inspect(toolNames).size();
    }

    private DashboardMcpAlertVO toAlertVO(McpAlertEventDTO alertEvent) {
        return DashboardMcpAlertVO.builder()
                .id(alertEvent.getId())
                .serverId(alertEvent.getServerId())
                .serverSlug(alertEvent.getServerSlug())
                .toolName(alertEvent.getToolName())
                .alertType(alertEvent.getAlertType())
                .severity(alertEvent.getSeverity())
                .summary(alertEvent.getSummary())
                .detailsJson(alertEvent.getDetailsJson())
                .createdAt(alertEvent.getCreatedAt())
                .updatedAt(alertEvent.getUpdatedAt())
                .build();
    }
}
