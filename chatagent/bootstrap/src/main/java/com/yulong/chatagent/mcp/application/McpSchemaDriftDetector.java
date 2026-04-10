package com.yulong.chatagent.mcp.application;

import com.yulong.chatagent.mcp.application.McpFeatureFlag;
import com.yulong.chatagent.mcp.application.McpToolNameNormalizer;
import com.yulong.chatagent.admin.port.McpToolCatalogRepository;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.mcp.metrics.McpMetricsRecorder;
import com.yulong.chatagent.mcp.model.McpDiscoveryResult;
import com.yulong.chatagent.mcp.model.McpRemoteToolDescriptor;
import com.yulong.chatagent.mcp.model.McpSchemaDriftOutcome;
import com.yulong.chatagent.mcp.runtime.McpRuntimeToolRegistry;
import com.yulong.chatagent.mcp.transport.McpTransportClient;
import com.yulong.chatagent.mcp.transport.McpTransportException;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import com.yulong.chatagent.support.enums.McpToolCatalogStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Background detector that compares cached MCP catalog rows with the live remote schema.
 */
@Component
@Slf4j
public class McpSchemaDriftDetector {

    private final McpFeatureFlag featureFlag;
    private final McpTransportClient transportClient;
    private final McpToolCatalogRepository toolCatalogRepository;
    private final McpToolNameNormalizer toolNameNormalizer;
    private final McpServerTestService serverTestService;
    private final McpMetricsRecorder metricsRecorder;
    private final McpRuntimeToolRegistry runtimeToolRegistry;

    public McpSchemaDriftDetector(McpFeatureFlag featureFlag,
                                  McpTransportClient transportClient,
                                  McpToolCatalogRepository toolCatalogRepository,
                                  McpToolNameNormalizer toolNameNormalizer,
                                  McpServerTestService serverTestService,
                                  McpMetricsRecorder metricsRecorder,
                                  McpRuntimeToolRegistry runtimeToolRegistry) {
        this.featureFlag = featureFlag;
        this.transportClient = transportClient;
        this.toolCatalogRepository = toolCatalogRepository;
        this.toolNameNormalizer = toolNameNormalizer;
        this.serverTestService = serverTestService;
        this.metricsRecorder = metricsRecorder;
        this.runtimeToolRegistry = runtimeToolRegistry;
    }

    public McpSchemaDriftOutcome detect(McpServerDTO server) {
        if (!featureFlag.isEnabled()) {
            return new McpSchemaDriftOutcome(false, false, 0, "MCP_DISABLED", "MCP outbound transport is disabled by configuration", server);
        }
        try {
            McpDiscoveryResult discoveryResult = transportClient.discover(server);
            LocalDateTime now = discoveryResult == null || discoveryResult.initializedAt() == null
                    ? LocalDateTime.now()
                    : discoveryResult.initializedAt();
            List<McpToolCatalogDTO> existingRows = toolCatalogRepository.findByServerId(server.getId());
            Map<String, McpToolCatalogDTO> existingByRemoteName = new HashMap<>();
            for (McpToolCatalogDTO row : existingRows) {
                existingByRemoteName.put(row.getRemoteOriginalName(), row);
            }

            int staleToolCount = 0;
            List<String> activeRemoteNames = discoveryResult.tools().stream()
                    .map(McpRemoteToolDescriptor::remoteOriginalName)
                    .toList();

            for (McpRemoteToolDescriptor tool : discoveryResult.tools()) {
                McpToolCatalogDTO existing = existingByRemoteName.get(tool.remoteOriginalName());
                String exposedModelName = toolNameNormalizer.normalizeToolName(server.getSlug(), tool.remoteOriginalName());
                if (!catalogChanged(existing, tool, exposedModelName)) {
                    continue;
                }
                staleToolCount++;
                McpToolCatalogDTO row = McpToolCatalogDTO.builder()
                        .id(existing == null ? UUID.randomUUID().toString() : existing.getId())
                        .serverId(server.getId())
                        .remoteOriginalName(tool.remoteOriginalName())
                        .toolDescription(tool.toolDescription())
                        .exposedModelName(exposedModelName)
                        .schemaJson(tool.schemaJson())
                        .schemaHash(tool.schemaHash())
                        .status(McpToolCatalogStatus.STALE)
                        .deletedAt(null)
                        .createdAt(existing == null ? now : existing.getCreatedAt())
                        .updatedAt(now)
                        .lastSyncedAt(now)
                        .build();
                if (!toolCatalogRepository.upsert(row)) {
                    throw new BizException("Failed to persist MCP schema drift row: " + tool.remoteOriginalName());
                }
            }

            staleToolCount += toolCatalogRepository.markMissingAsStale(server.getId(), activeRemoteNames, now, now);
            McpServerDTO updatedServer;
            if (staleToolCount > 0) {
                String message = "Detected MCP catalog drift; manual sync required before runtime re-enables the tool set";
                updatedServer = serverTestService.persistSchemaDriftDetected(server, discoveryResult, now, staleToolCount, message);
                metricsRecorder.recordSchemaDrift(server, staleToolCount);
                log.warn("MCP schema drift detected: serverId={}, serverSlug={}, staleToolCount={}",
                        server.getId(), server.getSlug(), staleToolCount);
            } else {
                updatedServer = serverTestService.persistProbeSuccess(server, discoveryResult);
            }
            runtimeToolRegistry.invalidate();
            return new McpSchemaDriftOutcome(true, staleToolCount > 0, staleToolCount, null, null, updatedServer);
        } catch (McpTransportException ex) {
            McpServerDTO updatedServer = serverTestService.persistSyncFailure(server, ex.getErrorCode(), ex.getMessage());
            runtimeToolRegistry.invalidate();
            metricsRecorder.recordSchemaDriftFailure(server, ex.getErrorCode());
            log.warn("MCP schema drift check failed: serverId={}, serverSlug={}, errorCode={}, message={}",
                    server.getId(), server.getSlug(), ex.getErrorCode(), ex.getMessage());
            return new McpSchemaDriftOutcome(false, false, 0, ex.getErrorCode(), ex.getMessage(), updatedServer);
        }
    }

    private boolean catalogChanged(McpToolCatalogDTO existing, McpRemoteToolDescriptor tool, String exposedModelName) {
        if (existing == null) {
            return true;
        }
        if (!safeEquals(existing.getToolDescription(), tool.toolDescription())) {
            return true;
        }
        if (!safeEquals(existing.getExposedModelName(), exposedModelName)) {
            return true;
        }
        if (!safeEquals(existing.getSchemaHash(), tool.schemaHash())) {
            return true;
        }
        if (!safeEquals(existing.getSchemaJson(), tool.schemaJson())) {
            return true;
        }
        return false;
    }

    private boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
