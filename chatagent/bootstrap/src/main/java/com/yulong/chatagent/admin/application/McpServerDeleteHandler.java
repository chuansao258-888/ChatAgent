package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.response.DeleteMcpServerResponse;
import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.admin.port.McpToolCatalogRepository;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.intent.application.IntentTreeCacheManager;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.port.IntentKnowledgeBaseRepository;
import com.yulong.chatagent.intent.port.IntentNodeRepository;
import com.yulong.chatagent.admin.application.McpAlertService;
import com.yulong.chatagent.mcp.application.McpServerReferenceInspector;
import com.yulong.chatagent.mcp.runtime.McpRuntimeToolRegistry;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import com.yulong.chatagent.support.dto.McpToolReferenceDTO;
import com.yulong.chatagent.support.enums.McpReferenceType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
class McpServerDeleteHandler {

    private final McpToolCatalogRepository mcpToolCatalogRepository;
    private final McpServerRepository mcpServerRepository;
    private final McpServerReferenceInspector referenceInspector;
    private final McpAlertService mcpAlertService;
    private final IntentNodeRepository intentNodeRepository;
    private final IntentKnowledgeBaseRepository intentKnowledgeBaseRepository;
    private final IntentTreeCacheManager intentTreeCacheManager;
    private final McpRuntimeToolRegistry mcpRuntimeToolRegistry;

    McpServerDeleteHandler(McpToolCatalogRepository mcpToolCatalogRepository,
                           McpServerRepository mcpServerRepository,
                           McpServerReferenceInspector referenceInspector,
                           McpAlertService mcpAlertService,
                           IntentNodeRepository intentNodeRepository,
                           IntentKnowledgeBaseRepository intentKnowledgeBaseRepository,
                           IntentTreeCacheManager intentTreeCacheManager,
                           McpRuntimeToolRegistry mcpRuntimeToolRegistry) {
        this.mcpToolCatalogRepository = mcpToolCatalogRepository;
        this.mcpServerRepository = mcpServerRepository;
        this.referenceInspector = referenceInspector;
        this.mcpAlertService = mcpAlertService;
        this.intentNodeRepository = intentNodeRepository;
        this.intentKnowledgeBaseRepository = intentKnowledgeBaseRepository;
        this.intentTreeCacheManager = intentTreeCacheManager;
        this.mcpRuntimeToolRegistry = mcpRuntimeToolRegistry;
    }

    @Transactional
    public DeleteMcpServerResponse execute(McpServerDTO server, boolean force) {
        List<McpToolCatalogDTO> catalogRows = mcpToolCatalogRepository.findByServerId(server.getId());
        List<String> toolNames = catalogRows.stream()
                .map(McpToolCatalogDTO::getExposedModelName)
                .filter(StringUtils::hasText)
                .toList();
        List<McpToolReferenceDTO> references = referenceInspector.inspect(toolNames);

        if (!force && !references.isEmpty()) {
            return new DeleteMcpServerResponse(
                    false, false,
                    references.size(), 0,
                    references
            );
        }

        int activeReferenceCount = references.size();
        if (force && !references.isEmpty()) {
            cleanupIntentNodeReferences(references, toolNames);
            references = referenceInspector.inspect(toolNames);
        }

        LocalDateTime now = LocalDateTime.now();
        if (!catalogRows.isEmpty()) {
            mcpToolCatalogRepository.softDeleteByServerId(server.getId(), now, now);
        }
        if (!mcpServerRepository.softDelete(server.getId(), now, now)) {
            throw new BizException("Failed to delete MCP server: " + server.getId());
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
                true, true,
                activeReferenceCount, references.size(),
                references
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
}
