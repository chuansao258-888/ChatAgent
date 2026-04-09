package com.yulong.chatagent.mcp.application;

import com.yulong.chatagent.admin.application.McpFeatureFlag;
import com.yulong.chatagent.admin.application.McpToolNameNormalizer;
import com.yulong.chatagent.admin.port.McpToolCatalogRepository;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.mcp.model.McpCatalogSyncOutcome;
import com.yulong.chatagent.mcp.model.McpDiscoveryResult;
import com.yulong.chatagent.mcp.model.McpRemoteToolDescriptor;
import com.yulong.chatagent.mcp.transport.McpTransportClient;
import com.yulong.chatagent.mcp.transport.McpTransportException;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import com.yulong.chatagent.support.enums.McpToolCatalogStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs tools/list and persists the cached MCP tool catalog.
 */
@Component
public class McpCatalogSyncService {

    private final McpFeatureFlag featureFlag;
    private final McpTransportClient transportClient;
    private final McpToolCatalogRepository toolCatalogRepository;
    private final McpToolNameNormalizer toolNameNormalizer;
    private final McpServerTestService serverTestService;

    public McpCatalogSyncService(McpFeatureFlag featureFlag,
                                 McpTransportClient transportClient,
                                 McpToolCatalogRepository toolCatalogRepository,
                                 McpToolNameNormalizer toolNameNormalizer,
                                 McpServerTestService serverTestService) {
        this.featureFlag = featureFlag;
        this.transportClient = transportClient;
        this.toolCatalogRepository = toolCatalogRepository;
        this.toolNameNormalizer = toolNameNormalizer;
        this.serverTestService = serverTestService;
    }

    public McpCatalogSyncOutcome sync(McpServerDTO server) {
        if (!featureFlag.isEnabled()) {
            return new McpCatalogSyncOutcome(false, "MCP_DISABLED", "MCP outbound transport is disabled by configuration", null, server, 0, 0, 0);
        }
        try {
            McpDiscoveryResult discoveryResult = transportClient.discover(server);
            LocalDateTime now = discoveryResult == null ? LocalDateTime.now() : discoveryResult.initializedAt();
            List<McpToolCatalogDTO> existingRows = toolCatalogRepository.findByServerId(server.getId());
            Map<String, McpToolCatalogDTO> existingByRemoteName = new HashMap<>();
            for (McpToolCatalogDTO row : existingRows) {
                existingByRemoteName.put(row.getRemoteOriginalName(), row);
            }

            int createdCount = 0;
            int updatedCount = 0;
            List<String> activeRemoteNames = discoveryResult.tools().stream()
                    .map(McpRemoteToolDescriptor::remoteOriginalName)
                    .toList();

            for (McpRemoteToolDescriptor tool : discoveryResult.tools()) {
                McpToolCatalogDTO existing = existingByRemoteName.get(tool.remoteOriginalName());
                String exposedModelName = toolNameNormalizer.normalizeToolName(server.getSlug(), tool.remoteOriginalName());
                if (existing == null) {
                    createdCount++;
                } else if (catalogChanged(existing, tool, exposedModelName)) {
                    updatedCount++;
                }

                McpToolCatalogDTO row = McpToolCatalogDTO.builder()
                        .id(existing == null ? UUID.randomUUID().toString() : existing.getId())
                        .serverId(server.getId())
                        .remoteOriginalName(tool.remoteOriginalName())
                        .toolDescription(tool.toolDescription())
                        .exposedModelName(exposedModelName)
                        .schemaJson(tool.schemaJson())
                        .schemaHash(tool.schemaHash())
                        .status(McpToolCatalogStatus.ENABLED)
                        .deletedAt(null)
                        .createdAt(existing == null ? now : existing.getCreatedAt())
                        .updatedAt(now)
                        .lastSyncedAt(now)
                        .build();
                if (!toolCatalogRepository.upsert(row)) {
                    throw new BizException("Failed to upsert MCP tool catalog row: " + tool.remoteOriginalName());
                }
            }

            int staleCount = toolCatalogRepository.markMissingAsStale(server.getId(), activeRemoteNames, now, now);
            McpServerDTO updatedServer = serverTestService.persistSyncSuccess(server, discoveryResult, now);
            return new McpCatalogSyncOutcome(true, null, null, discoveryResult, updatedServer, createdCount, updatedCount, staleCount);
        } catch (McpTransportException ex) {
            McpServerDTO updated = serverTestService.persistSyncFailure(server, ex.getErrorCode(), ex.getMessage());
            return new McpCatalogSyncOutcome(false, ex.getErrorCode(), ex.getMessage(), null, updated, 0, 0, 0);
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
        return existing.getStatus() != McpToolCatalogStatus.ENABLED;
    }

    private boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
