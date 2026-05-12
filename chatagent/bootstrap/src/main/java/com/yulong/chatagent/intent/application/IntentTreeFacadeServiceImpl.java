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
 * 意图树后台编排服务。
 *
 * 这里可以理解成「意图树编辑器」的应用层：
 * 1. draft(version = 0) 用于后台编辑，可以反复增删改；
 * 2. publish 时复制成不可变的 PUBLISHED 版本，供运行时路由读取；
 * 3. activeIntentVersion 记录当前线上生效版本；
 * 4. 每次发布/切换版本后刷新 IntentTreeCacheManager，避免运行时继续读旧快照。
 */
@Service
@RequiredArgsConstructor
public class IntentTreeFacadeServiceImpl implements IntentTreeFacadeService {

    /**
     * 约定 version = 0 永远表示草稿树。
     *
     * 已发布版本从 1 开始递增，所以运行时只会读取 version > 0 的 PUBLISHED 节点。
     */
    private static final int DRAFT_VERSION = 0;

    private final InternalAssistantService internalAssistantService;
    private final IntentNodeRepository intentNodeRepository;
    private final IntentKnowledgeBaseRepository intentKnowledgeBaseRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final IntentTreeCacheManager intentTreeCacheManager;

    @Override
    public GetIntentTreeResponse getIntentTree() {
        AgentDTO assistant = internalAssistantService.getRequiredAssistant();
        // 后台编辑页展示的是草稿节点，同时带上当前 active version 和历史版本列表。
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
        // 创建时先校验层级关系，避免后台保存出运行时无法正确逐层路由的结构。
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
        // PATCH 语义：请求里没传的字段沿用已有值；传了 parent/level 时才尝试移动节点。
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
            // 节点从 KB 改成 TOOL/SYSTEM 后，原先的知识库绑定已经没有意义，需要清理。
            intentKnowledgeBaseRepository.deleteByIntentNodeIds(List.of(nodeId));
        }
    }

    @Override
    @Transactional
    public void deleteIntentNode(String nodeId) {
        AgentDTO assistant = internalAssistantService.getRequiredAssistant();
        List<IntentNodeDTO> draftNodes = loadDraftNodes(assistant.getId());
        requireDraftNode(nodeId, assistant.getId(), draftNodes);

        // 删除不是只删单个节点，而是按 parentId 关系收集整棵子树，连同绑定一起删。
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

        // 绑定前要求知识库存在且 ACTIVE，避免运行时命中一个不可检索/已下线的知识库。
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
            // 发布时重新生成节点 id，而不是复用 draft id。
            // 这样 draft 后续继续编辑时，不会影响已经发布出去的历史 snapshot。
            publishedIdByDraftId.put(draftNode.getId(), UUID.randomUUID().toString());
        }

        List<IntentNodeDTO> publishedNodes = draftNodes.stream()
                .map(draftNode -> IntentNodeDTO.builder()
                        .id(publishedIdByDraftId.get(draftNode.getId()))
                        .agentId(draftNode.getAgentId())
                        // parentId 也必须映射成发布节点的新 id，保证发布版本内部自洽。
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
            // 知识库绑定表存的是 intentNodeId，所以发布节点换 id 后，绑定关系也要复制一份。
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
        // 让下一轮会话准备阶段读到新版本；否则 Redis TTL 内可能继续使用旧 snapshot。
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
        // 切版本时也刷新 active snapshot，因为 cache key 只按 agentId 存 active。
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
        // 草稿树固定存 version = 0；后台所有 CRUD 都围绕这个版本操作。
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
            // 一个 KB intent 可以绑定多个知识库，所以这里按 nodeId 聚合成 List。
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

    /**
     * 校验意图树的结构约束。
     *
     * 运行时 IntentRouter 是按 DOMAIN -> CATEGORY -> TOPIC 逐层向下打分的，
     * 所以后台必须保证树的层级清晰，否则路由时 childrenOf/current path 会失去语义。
     */
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
            // 移动节点时不能把它挂到自己的子孙节点下面，否则 parentId 链路会形成环。
            Set<String> descendantIds = new LinkedHashSet<>(collectSubtreeIds(existingNode.getId(), draftNodes));
            if (descendantIds.contains(parent.getId())) {
                throw new BizException("Intent node cannot be moved under its own descendant");
            }
        }
    }

    private List<String> collectSubtreeIds(String rootNodeId, List<IntentNodeDTO> draftNodes) {
        Map<String, List<String>> childrenByParentId = new LinkedHashMap<>();
        for (IntentNodeDTO draftNode : draftNodes) {
            // 数据库存的是扁平 id + parent_id，这里临时聚合成 parent -> children 的索引。
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
        // 这里返回 id 列表即可；真正的级联删除依赖 deleteByIds，不依赖顺序。
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
            // 非叶子节点只承担分类，不代表最终处理类型。
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
            // KB 意图允许配置是否 fallback 到全局知识库；默认允许，避免绑定缺失时完全不可答。
            return scopePolicy == null ? com.yulong.chatagent.intent.model.ScopePolicy.FALLBACK_ALLOWED : scopePolicy;
        }
        // TOOL/SYSTEM 不走知识库检索，scopePolicy 固定为 STRICT，避免运行时误以为可以扩散检索范围。
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
