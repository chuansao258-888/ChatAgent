package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.application.InternalAssistantService;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.IntentNodeLevel;
import com.yulong.chatagent.intent.model.IntentNodeStatus;
import com.yulong.chatagent.intent.model.request.SetIntentNodeKnowledgeBasesRequest;
import com.yulong.chatagent.intent.model.request.UpsertIntentNodeRequest;
import com.yulong.chatagent.intent.model.response.CreateIntentNodeResponse;
import com.yulong.chatagent.intent.model.response.GetIntentTreeResponse;
import com.yulong.chatagent.intent.model.vo.IntentNodeVO;
import com.yulong.chatagent.intent.model.vo.IntentVersionVO;
import com.yulong.chatagent.intent.port.IntentKnowledgeBaseRepository;
import com.yulong.chatagent.intent.port.IntentNodeRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.IntentKnowledgeBaseDTO;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Back-office intent-tree management for draft CRUD, publish, and version switching.
 */
@Service
@RequiredArgsConstructor
public class IntentTreeFacadeServiceImpl implements IntentTreeFacadeService {

    private static final int DRAFT_VERSION = 0;

    private final InternalAssistantService internalAssistantService;
    private final IntentNodeRepository intentNodeRepository;
    private final IntentKnowledgeBaseRepository intentKnowledgeBaseRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final IntentTreeCacheManager intentTreeCacheManager;

    @Override
    public GetIntentTreeResponse getIntentTree() {
        AgentDTO assistant = internalAssistantService.getRequiredAssistant();
        List<IntentNodeDTO> draftNodes = loadDraftNodes(assistant.getId());
        Map<String, List<String>> knowledgeBaseIdsByNodeId = loadKnowledgeBaseIdsByNodeId(draftNodes);
        return new GetIntentTreeResponse(
                assistant.getActiveIntentVersion(),
                buildVersionVos(assistant.getId(), assistant.getActiveIntentVersion()),
                draftNodes.stream()
                        .map(node -> toNodeVO(node, knowledgeBaseIdsByNodeId.getOrDefault(node.getId(), List.of())))
                        .toList()
        );
    }

    @Override
    @Transactional
    public CreateIntentNodeResponse createIntentNode(UpsertIntentNodeRequest request) {
        AgentDTO assistant = internalAssistantService.getRequiredAssistant();
        List<IntentNodeDTO> draftNodes = loadDraftNodes(assistant.getId());
        IntentNodeDTO parent = resolveParent(request.getParentId(), draftNodes);
        validateNodePlacement(null, parent, request.getNodeLevel(), draftNodes);

        LocalDateTime now = LocalDateTime.now();
        IntentNodeDTO node = IntentNodeDTO.builder()
                .id(UUID.randomUUID().toString())
                .agentId(assistant.getId())
                .parentId(emptyToNull(request.getParentId()))
                .version(DRAFT_VERSION)
                .status(IntentNodeStatus.DRAFT)
                .nodeLevel(requireNodeLevel(request.getNodeLevel()))
                .name(requireName(request.getName()))
                .description(normalizeNullableText(request.getDescription()))
                .examples(normalizeList(request.getExamples()))
                .intentKind(resolveIntentKind(request.getNodeLevel(), request.getIntentKind()))
                .scopePolicy(resolveScopePolicy(request.getNodeLevel(), request.getIntentKind(), request.getScopePolicy()))
                .allowedTools(resolveAllowedTools(request.getNodeLevel(), request.getIntentKind(), request.getAllowedTools()))
                .systemPromptOverride(resolveSystemPromptOverride(request.getNodeLevel(), request.getIntentKind(), request.getSystemPromptOverride()))
                .enabled(request.getEnabled() == null ? Boolean.TRUE : request.getEnabled())
                .sortOrder(request.getSortOrder() == null ? draftNodes.size() : request.getSortOrder())
                .createdAt(now)
                .updatedAt(now)
                .build();
        if (!intentNodeRepository.save(node)) {
            throw new BizException("Failed to create intent node");
        }
        return new CreateIntentNodeResponse(node.getId());
    }

    @Override
    @Transactional
    public void updateIntentNode(String nodeId, UpsertIntentNodeRequest request) {
        AgentDTO assistant = internalAssistantService.getRequiredAssistant();
        List<IntentNodeDTO> draftNodes = loadDraftNodes(assistant.getId());
        IntentNodeDTO existing = requireDraftNode(nodeId, assistant.getId(), draftNodes);
        String targetParentId = request.getParentId() != null ? emptyToNull(request.getParentId()) : existing.getParentId();
        IntentNodeLevel targetNodeLevel = request.getNodeLevel() != null ? request.getNodeLevel() : existing.getNodeLevel();
        IntentNodeDTO parent = resolveParent(targetParentId, draftNodes, nodeId);
        validateNodePlacement(existing, parent, targetNodeLevel, draftNodes);

        IntentKind targetIntentKind = resolveIntentKind(
                targetNodeLevel,
                request.getIntentKind() != null ? request.getIntentKind() : existing.getIntentKind()
        );

        IntentNodeDTO updated = IntentNodeDTO.builder()
                .id(existing.getId())
                .agentId(existing.getAgentId())
                .parentId(targetParentId)
                .version(existing.getVersion())
                .status(existing.getStatus())
                .nodeLevel(targetNodeLevel)
                .name(request.getName() != null ? requireName(request.getName()) : existing.getName())
                .description(request.getDescription() != null ? normalizeNullableText(request.getDescription()) : existing.getDescription())
                .examples(request.getExamples() != null ? normalizeList(request.getExamples()) : existing.getExamples())
                .intentKind(targetIntentKind)
                .scopePolicy(resolveScopePolicy(
                        targetNodeLevel,
                        targetIntentKind,
                        request.getScopePolicy() != null ? request.getScopePolicy() : existing.getScopePolicy()
                ))
                .allowedTools(resolveAllowedTools(
                        targetNodeLevel,
                        targetIntentKind,
                        request.getAllowedTools() != null ? request.getAllowedTools() : existing.getAllowedTools()
                ))
                .systemPromptOverride(resolveSystemPromptOverride(
                        targetNodeLevel,
                        targetIntentKind,
                        request.getSystemPromptOverride() != null ? request.getSystemPromptOverride() : existing.getSystemPromptOverride()
                ))
                .enabled(request.getEnabled() != null ? request.getEnabled() : existing.getEnabled())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : existing.getSortOrder())
                .createdAt(existing.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();

        if (!intentNodeRepository.update(updated)) {
            throw new BizException("Failed to update intent node: " + nodeId);
        }

        if (updated.getIntentKind() != IntentKind.KB) {
            intentKnowledgeBaseRepository.deleteByIntentNodeIds(List.of(nodeId));
        }
    }

    @Override
    @Transactional
    public void deleteIntentNode(String nodeId) {
        AgentDTO assistant = internalAssistantService.getRequiredAssistant();
        List<IntentNodeDTO> draftNodes = loadDraftNodes(assistant.getId());
        requireDraftNode(nodeId, assistant.getId(), draftNodes);

        List<String> subtreeIds = collectSubtreeIds(nodeId, draftNodes);
        intentKnowledgeBaseRepository.deleteByIntentNodeIds(subtreeIds);
        if (!intentNodeRepository.deleteByIds(subtreeIds)) {
            throw new BizException("Failed to delete intent node: " + nodeId);
        }
    }

    @Override
    @Transactional
    public void setIntentNodeKnowledgeBases(String nodeId, SetIntentNodeKnowledgeBasesRequest request) {
        AgentDTO assistant = internalAssistantService.getRequiredAssistant();
        IntentNodeDTO node = requireDraftNode(nodeId, assistant.getId(), loadDraftNodes(assistant.getId()));
        if (node.getIntentKind() != IntentKind.KB) {
            throw new BizException("Only KB intent nodes can bind knowledge bases");
        }

        List<String> requestedIds = normalizeList(request == null ? null : request.getKnowledgeBaseIds());
        List<String> activeIds = knowledgeBaseRepository.filterActiveIds(requestedIds);
        if (requestedIds.size() != activeIds.size()) {
            throw new BizException("Only existing ACTIVE knowledge bases can be bound to one intent node");
        }

        intentKnowledgeBaseRepository.deleteByIntentNodeIds(List.of(nodeId));
        if (activeIds.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<IntentKnowledgeBaseDTO> bindings = activeIds.stream()
                .map(knowledgeBaseId -> IntentKnowledgeBaseDTO.builder()
                        .id(UUID.randomUUID().toString())
                        .intentNodeId(nodeId)
                        .knowledgeBaseId(knowledgeBaseId)
                        .createdAt(now)
                        .build())
                .toList();
        if (!intentKnowledgeBaseRepository.saveAll(bindings)) {
            throw new BizException("Failed to update knowledge-base bindings for intent node: " + nodeId);
        }
    }

    @Override
    @Transactional
    public Integer publishIntentTreeSnapshot() {
        AgentDTO assistant = internalAssistantService.getRequiredAssistant();
        List<IntentNodeDTO> draftNodes = loadDraftNodes(assistant.getId());
        if (draftNodes.isEmpty()) {
            throw new BizException("No draft intent nodes found for publishing");
        }

        int nextVersion = Math.max(1, defaultIfNull(intentNodeRepository.findMaxVersion(assistant.getId()), 0) + 1);
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> publishedIdByDraftId = new LinkedHashMap<>();
        for (IntentNodeDTO draftNode : draftNodes) {
            publishedIdByDraftId.put(draftNode.getId(), UUID.randomUUID().toString());
        }

        List<IntentNodeDTO> publishedNodes = draftNodes.stream()
                .map(draftNode -> IntentNodeDTO.builder()
                        .id(publishedIdByDraftId.get(draftNode.getId()))
                        .agentId(draftNode.getAgentId())
                        .parentId(draftNode.getParentId() == null ? null : publishedIdByDraftId.get(draftNode.getParentId()))
                        .version(nextVersion)
                        .status(IntentNodeStatus.PUBLISHED)
                        .nodeLevel(draftNode.getNodeLevel())
                        .name(draftNode.getName())
                        .description(draftNode.getDescription())
                        .examples(normalizeList(draftNode.getExamples()))
                        .intentKind(draftNode.getIntentKind())
                        .scopePolicy(draftNode.getScopePolicy())
                        .allowedTools(normalizeList(draftNode.getAllowedTools()))
                        .systemPromptOverride(draftNode.getSystemPromptOverride())
                        .enabled(Boolean.TRUE.equals(draftNode.getEnabled()))
                        .sortOrder(draftNode.getSortOrder())
                        .createdAt(now)
                        .updatedAt(now)
                        .build())
                .toList();
        if (!intentNodeRepository.saveAll(publishedNodes)) {
            throw new BizException("Failed to publish intent snapshot");
        }

        List<IntentKnowledgeBaseDTO> draftBindings = intentKnowledgeBaseRepository.findByIntentNodeIds(
                draftNodes.stream().map(IntentNodeDTO::getId).toList()
        );
        if (!draftBindings.isEmpty()) {
            List<IntentKnowledgeBaseDTO> publishedBindings = draftBindings.stream()
                    .map(binding -> IntentKnowledgeBaseDTO.builder()
                            .id(UUID.randomUUID().toString())
                            .intentNodeId(publishedIdByDraftId.get(binding.getIntentNodeId()))
                            .knowledgeBaseId(binding.getKnowledgeBaseId())
                            .createdAt(now)
                            .build())
                    .toList();
            if (!intentKnowledgeBaseRepository.saveAll(publishedBindings)) {
                throw new BizException("Failed to publish intent knowledge-base bindings");
            }
        }

        if (!internalAssistantService.updateActiveIntentVersion(nextVersion)) {
            throw new BizException("Failed to switch active intent version");
        }
        intentTreeCacheManager.refreshActiveSnapshot(assistant.getId());
        return nextVersion;
    }

    @Override
    public List<IntentVersionVO> getIntentVersions() {
        AgentDTO assistant = internalAssistantService.getRequiredAssistant();
        return buildVersionVos(assistant.getId(), assistant.getActiveIntentVersion());
    }

    @Override
    @Transactional
    public void switchActiveIntentVersion(int version) {
        AgentDTO assistant = internalAssistantService.getRequiredAssistant();
        if (version <= 0) {
            throw new BizException("Version must be greater than 0");
        }
        if (intentNodeRepository.findByAgentIdAndVersion(assistant.getId(), version).isEmpty()) {
            throw new BizException("Published intent version not found: " + version);
        }
        if (!internalAssistantService.updateActiveIntentVersion(version)) {
            throw new BizException("Failed to activate intent version: " + version);
        }
        intentTreeCacheManager.refreshActiveSnapshot(assistant.getId());
    }

    @Override
    @Transactional
    public void clearDraftTree() {
        AgentDTO assistant = internalAssistantService.getRequiredAssistant();
        List<IntentNodeDTO> draftNodes = loadDraftNodes(assistant.getId());
        if (draftNodes.isEmpty()) {
            return;
        }
        List<String> ids = draftNodes.stream().map(IntentNodeDTO::getId).toList();
        if (!intentNodeRepository.deleteByIds(ids)) {
            throw new BizException("Failed to clear the existing draft intent tree");
        }
    }

    private List<IntentNodeDTO> loadDraftNodes(String agentId) {
        return intentNodeRepository.findByAgentIdAndVersion(agentId, DRAFT_VERSION);
    }

    private Map<String, List<String>> loadKnowledgeBaseIdsByNodeId(List<IntentNodeDTO> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> knowledgeBaseIdsByNodeId = new LinkedHashMap<>();
        for (IntentKnowledgeBaseDTO binding : intentKnowledgeBaseRepository.findByIntentNodeIds(
                nodes.stream().map(IntentNodeDTO::getId).toList()
        )) {
            knowledgeBaseIdsByNodeId.computeIfAbsent(binding.getIntentNodeId(), ignored -> new ArrayList<>())
                    .add(binding.getKnowledgeBaseId());
        }
        return knowledgeBaseIdsByNodeId;
    }

    private List<IntentVersionVO> buildVersionVos(String agentId, Integer activeVersion) {
        List<Integer> versions = intentNodeRepository.findPublishedVersions(agentId);
        return versions.stream()
                .map(version -> new IntentVersionVO(version, Objects.equals(version, activeVersion)))
                .toList();
    }

    private IntentNodeVO toNodeVO(IntentNodeDTO node, List<String> knowledgeBaseIds) {
        return IntentNodeVO.builder()
                .id(node.getId())
                .parentId(node.getParentId())
                .version(node.getVersion())
                .status(node.getStatus())
                .nodeLevel(node.getNodeLevel())
                .name(node.getName())
                .description(node.getDescription())
                .examples(normalizeList(node.getExamples()))
                .intentKind(node.getIntentKind())
                .scopePolicy(node.getScopePolicy())
                .allowedTools(normalizeList(node.getAllowedTools()))
                .systemPromptOverride(node.getSystemPromptOverride())
                .enabled(node.getEnabled())
                .sortOrder(node.getSortOrder())
                .knowledgeBaseIds(knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds))
                .build();
    }

    private IntentNodeDTO requireDraftNode(String nodeId, String agentId, List<IntentNodeDTO> draftNodes) {
        IntentNodeDTO node = draftNodes.stream()
                .filter(candidate -> nodeId.equals(candidate.getId()))
                .findFirst()
                .orElse(null);
        if (node == null || !agentId.equals(node.getAgentId()) || node.getVersion() == null || node.getVersion() != DRAFT_VERSION) {
            throw new BizException("Draft intent node not found: " + nodeId);
        }
        return node;
    }

    private IntentNodeDTO resolveParent(String parentId, List<IntentNodeDTO> draftNodes) {
        return resolveParent(parentId, draftNodes, null);
    }

    private IntentNodeDTO resolveParent(String parentId, List<IntentNodeDTO> draftNodes, String selfNodeId) {
        String normalizedParentId = emptyToNull(parentId);
        if (!StringUtils.hasText(normalizedParentId)) {
            return null;
        }
        IntentNodeDTO parent = draftNodes.stream()
                .filter(node -> normalizedParentId.equals(node.getId()))
                .findFirst()
                .orElseThrow(() -> new BizException("Parent draft intent node not found: " + normalizedParentId));
        if (selfNodeId != null && selfNodeId.equals(parent.getId())) {
            throw new BizException("Intent node cannot be its own parent");
        }
        return parent;
    }

    private void validateNodePlacement(IntentNodeDTO existingNode,
                                       IntentNodeDTO parent,
                                       IntentNodeLevel targetLevel,
                                       List<IntentNodeDTO> draftNodes) {
        if (targetLevel == null) {
            throw new BizException("Intent node level is required");
        }
        if (parent == null) {
            if (targetLevel != IntentNodeLevel.DOMAIN) {
                throw new BizException("Root intent nodes must use DOMAIN level");
            }
        } else if (parent.getNodeLevel() == IntentNodeLevel.DOMAIN) {
            if (targetLevel != IntentNodeLevel.CATEGORY) {
                throw new BizException("Children of DOMAIN nodes must use CATEGORY level");
            }
        } else if (parent.getNodeLevel() == IntentNodeLevel.CATEGORY) {
            if (targetLevel != IntentNodeLevel.TOPIC) {
                throw new BizException("Children of CATEGORY nodes must use TOPIC level");
            }
        } else {
            throw new BizException("TOPIC nodes cannot have child nodes");
        }

        if (existingNode != null && parent != null) {
            Set<String> descendantIds = new LinkedHashSet<>(collectSubtreeIds(existingNode.getId(), draftNodes));
            if (descendantIds.contains(parent.getId())) {
                throw new BizException("Intent node cannot be moved under its own descendant");
            }
        }
    }

    private List<String> collectSubtreeIds(String rootNodeId, List<IntentNodeDTO> draftNodes) {
        Map<String, List<String>> childrenByParentId = new LinkedHashMap<>();
        for (IntentNodeDTO draftNode : draftNodes) {
            childrenByParentId.computeIfAbsent(emptyToNull(draftNode.getParentId()), ignored -> new ArrayList<>())
                    .add(draftNode.getId());
        }

        List<String> subtreeIds = new ArrayList<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(rootNodeId);
        while (!queue.isEmpty()) {
            String currentId = queue.removeFirst();
            subtreeIds.add(currentId);
            for (String childId : childrenByParentId.getOrDefault(currentId, List.of())) {
                queue.addLast(childId);
            }
        }
        subtreeIds.sort(Comparator.reverseOrder());
        return subtreeIds;
    }

    private String requireName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BizException("Intent node name is required");
        }
        return name.trim();
    }

    private IntentNodeLevel requireNodeLevel(IntentNodeLevel nodeLevel) {
        if (nodeLevel == null) {
            throw new BizException("Intent node level is required");
        }
        return nodeLevel;
    }

    private IntentKind resolveIntentKind(IntentNodeLevel nodeLevel, IntentKind intentKind) {
        if (nodeLevel != IntentNodeLevel.TOPIC) {
            return null;
        }
        if (intentKind == null) {
            throw new BizException("TOPIC nodes must declare an intent kind");
        }
        return intentKind;
    }

    private com.yulong.chatagent.intent.model.ScopePolicy resolveScopePolicy(IntentNodeLevel nodeLevel,
                                                                             IntentKind intentKind,
                                                                             com.yulong.chatagent.intent.model.ScopePolicy scopePolicy) {
        if (nodeLevel != IntentNodeLevel.TOPIC || intentKind == null) {
            return null;
        }
        if (intentKind == IntentKind.KB) {
            return scopePolicy == null ? com.yulong.chatagent.intent.model.ScopePolicy.FALLBACK_ALLOWED : scopePolicy;
        }
        return com.yulong.chatagent.intent.model.ScopePolicy.STRICT;
    }

    private List<String> resolveAllowedTools(IntentNodeLevel nodeLevel, IntentKind intentKind, List<String> allowedTools) {
        if (nodeLevel != IntentNodeLevel.TOPIC || intentKind != IntentKind.TOOL) {
            return List.of();
        }
        return normalizeList(allowedTools);
    }

    private String resolveSystemPromptOverride(IntentNodeLevel nodeLevel, IntentKind intentKind, String systemPromptOverride) {
        if (nodeLevel != IntentNodeLevel.TOPIC || intentKind != IntentKind.SYSTEM) {
            return null;
        }
        return normalizeNullableText(systemPromptOverride);
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String normalizeNullableText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private int defaultIfNull(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }
}
