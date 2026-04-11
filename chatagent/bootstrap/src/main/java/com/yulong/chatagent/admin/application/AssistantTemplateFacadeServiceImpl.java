package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.admin.model.request.InitializeAssistantFromTemplateRequest;
import com.yulong.chatagent.admin.model.request.UpsertAssistantTemplateRequest;
import com.yulong.chatagent.admin.model.response.InitializeAssistantFromTemplateResponse;
import com.yulong.chatagent.admin.model.vo.AssistantTemplateVO;
import com.yulong.chatagent.agent.port.AgentKnowledgeBaseRepository;
import com.yulong.chatagent.agent.port.AgentRepository;
import com.yulong.chatagent.agent.port.AssistantTemplateRepository;
import com.yulong.chatagent.agent.application.InternalAssistantService;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.intent.application.IntentTreeFacadeService;
import com.yulong.chatagent.intent.model.IntentNodeLevel;
import com.yulong.chatagent.intent.model.request.SetIntentNodeKnowledgeBasesRequest;
import com.yulong.chatagent.intent.model.request.UpsertIntentNodeRequest;
import com.yulong.chatagent.intent.model.response.CreateIntentNodeResponse;
import com.yulong.chatagent.intent.port.IntentNodeRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.AssistantTemplateDTO;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Handles template CRUD plus the transactional initialize-from-template flow.
 */
@Service
@RequiredArgsConstructor
public class AssistantTemplateFacadeServiceImpl implements AssistantTemplateFacadeService {

    private static final int DRAFT_VERSION = 0;

    private final AdminAccessService adminAccessService;
    private final AssistantTemplateRepository assistantTemplateRepository;
    private final InternalAssistantService internalAssistantService;
    private final AgentRepository agentRepository;
    private final AgentKnowledgeBaseRepository agentKnowledgeBaseRepository;
    private final IntentNodeRepository intentNodeRepository;
    private final IntentTreeFacadeService intentTreeFacadeService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ResourceAccessGuard resourceAccessGuard;

    @Override
    public List<AssistantTemplateVO> getTemplates() {
        adminAccessService.requireAdmin();
        return assistantTemplateRepository.findAll().stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public AssistantTemplateVO getTemplate(String templateId) {
        adminAccessService.requireAdmin();
        return toVO(requireTemplate(templateId));
    }

    @Override
    @Transactional
    public String createTemplate(UpsertAssistantTemplateRequest request) {
        adminAccessService.requireAdmin();
        AssistantTemplateDTO template = buildTemplateForCreate(request);
        if (!assistantTemplateRepository.save(template)) {
            throw new BizException("Failed to create assistant template");
        }
        return template.getId();
    }

    @Override
    @Transactional
    public void updateTemplate(String templateId, UpsertAssistantTemplateRequest request) {
        adminAccessService.requireAdmin();
        AssistantTemplateDTO existing = requireTemplate(templateId);
        AssistantTemplateDTO updated = buildTemplateForUpdate(existing, request);
        if (!assistantTemplateRepository.update(updated)) {
            throw new BizException("Failed to update assistant template: " + templateId);
        }
    }

    @Override
    @Transactional
    public void deleteTemplate(String templateId) {
        adminAccessService.requireAdmin();
        requireTemplate(templateId);
        if (!assistantTemplateRepository.deleteById(templateId)) {
            throw new BizException("Failed to delete assistant template: " + templateId);
        }
    }

    /**
     * Applies a template to the internal assistant and recreates the draft intent tree in one transaction.
     */
    @Override
    @Transactional
    public InitializeAssistantFromTemplateResponse initializeAssistantFromTemplate(String templateId,
                                                                                   InitializeAssistantFromTemplateRequest request) {
        LoginUser adminUser = adminAccessService.requireAdmin();
        AssistantTemplateDTO template = requireTemplate(templateId);
        List<String> selectedKnowledgeBaseIds = normalizeKnowledgeBaseIds(
                request == null ? null : request.getKnowledgeBaseIds()
        );
        validateKnowledgeBaseIds(adminUser, selectedKnowledgeBaseIds);

        AgentDTO assistant = internalAssistantService.getRequiredAssistant();
        assistant.setName(template.getName());
        assistant.setDescription(template.getDescription());
        assistant.setSystemPrompt(template.getSystemPrompt());
        assistant.setModel(template.getModel());
        assistant.setAllowedTools(template.getAllowedTools());
        assistant.setChatOptions(template.getChatOptions());
        if (!agentRepository.update(assistant)) {
            throw new BizException("Failed to update the internal assistant from template");
        }

        agentKnowledgeBaseRepository.replaceBindings(assistant.getId(), selectedKnowledgeBaseIds);
        clearDraftTree(assistant.getId());

        Map<String, String> createdNodeIdsByCode = new LinkedHashMap<>();
        for (AssistantTemplateDTO.IntentTreeNodeTemplateDTO nodeTemplate : template.getIntentTree()) {
            UpsertIntentNodeRequest createRequest = new UpsertIntentNodeRequest();
            createRequest.setParentId(resolveParentId(nodeTemplate.getParentCode(), createdNodeIdsByCode));
            createRequest.setNodeLevel(nodeTemplate.getNodeLevel());
            createRequest.setName(nodeTemplate.getName());
            createRequest.setDescription(nodeTemplate.getDescription());
            createRequest.setExamples(nodeTemplate.getExamples());
            createRequest.setIntentKind(nodeTemplate.getIntentKind());
            createRequest.setScopePolicy(nodeTemplate.getScopePolicy());
            createRequest.setAllowedTools(nodeTemplate.getAllowedTools());
            createRequest.setSystemPromptOverride(nodeTemplate.getSystemPromptOverride());
            createRequest.setEnabled(nodeTemplate.getEnabled());
            createRequest.setSortOrder(nodeTemplate.getSortOrder());

            CreateIntentNodeResponse response = intentTreeFacadeService.createIntentNode(createRequest);
            createdNodeIdsByCode.put(nodeTemplate.getCode(), response.getNodeId());

            if (Boolean.TRUE.equals(nodeTemplate.getBindSelectedKnowledgeBases())
                    && nodeTemplate.getNodeLevel() == IntentNodeLevel.TOPIC
                    && !selectedKnowledgeBaseIds.isEmpty()) {
                SetIntentNodeKnowledgeBasesRequest bindingRequest = new SetIntentNodeKnowledgeBasesRequest();
                bindingRequest.setKnowledgeBaseIds(selectedKnowledgeBaseIds);
                intentTreeFacadeService.setIntentNodeKnowledgeBases(response.getNodeId(), bindingRequest);
            }
        }

        Integer publishedVersion = intentTreeFacadeService.publishIntentTreeSnapshot();
        return new InitializeAssistantFromTemplateResponse(templateId, publishedVersion);
    }

    private AssistantTemplateDTO buildTemplateForCreate(UpsertAssistantTemplateRequest request) {
        LocalDateTime now = LocalDateTime.now();
        return AssistantTemplateDTO.builder()
                .id(UUID.randomUUID().toString())
                .code(requireUniqueCode(normalizeCode(request == null ? null : request.getCode()), null))
                .name(requireName(request == null ? null : request.getName()))
                .description(normalizeNullableText(request == null ? null : request.getDescription()))
                .systemPrompt(requireSystemPrompt(request == null ? null : request.getSystemPrompt()))
                .model(requireModel(request == null ? null : request.getModel()))
                .allowedTools(normalizeStringList(request == null ? null : request.getAllowedTools()))
                .chatOptions(normalizeChatOptions(request == null ? null : request.getChatOptions()))
                .intentTree(normalizeIntentTree(request == null ? null : request.getIntentTree()))
                .builtIn(Boolean.FALSE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private AssistantTemplateDTO buildTemplateForUpdate(AssistantTemplateDTO existing, UpsertAssistantTemplateRequest request) {
        return AssistantTemplateDTO.builder()
                .id(existing.getId())
                .code(requireUniqueCode(
                        normalizeCode(request != null && request.getCode() != null ? request.getCode() : existing.getCode()),
                        existing.getId()
                ))
                .name(requireName(request != null && request.getName() != null ? request.getName() : existing.getName()))
                .description(normalizeNullableText(request != null && request.getDescription() != null
                        ? request.getDescription()
                        : existing.getDescription()))
                .systemPrompt(requireSystemPrompt(request != null && request.getSystemPrompt() != null
                        ? request.getSystemPrompt()
                        : existing.getSystemPrompt()))
                .model(request != null && request.getModel() != null
                        ? requireModel(request.getModel())
                        : existing.getModel())
                .allowedTools(request != null && request.getAllowedTools() != null
                        ? normalizeStringList(request.getAllowedTools())
                        : existing.getAllowedTools())
                .chatOptions(request != null && request.getChatOptions() != null
                        ? normalizeChatOptions(request.getChatOptions())
                        : existing.getChatOptions())
                .intentTree(request != null && request.getIntentTree() != null
                        ? normalizeIntentTree(request.getIntentTree())
                        : existing.getIntentTree())
                .builtIn(existing.getBuiltIn())
                .createdAt(existing.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private void validateKnowledgeBaseIds(LoginUser adminUser, List<String> knowledgeBaseIds) {
        if (knowledgeBaseIds.isEmpty()) {
            return;
        }
        for (String knowledgeBaseId : knowledgeBaseIds) {
            resourceAccessGuard.assertCanManageKnowledgeBase(adminUser, knowledgeBaseId);
        }
        if (knowledgeBaseRepository.filterActiveIds(knowledgeBaseIds).size() != knowledgeBaseIds.size()) {
            throw new BizException("Only active knowledge bases can be bound during template initialization");
        }
    }

    private void clearDraftTree(String assistantId) {
        List<IntentNodeDTO> draftNodes = intentNodeRepository.findByAgentIdAndVersion(assistantId, DRAFT_VERSION);
        if (draftNodes.isEmpty()) {
            return;
        }
        List<String> ids = draftNodes.stream().map(IntentNodeDTO::getId).toList();
        if (!intentNodeRepository.deleteByIds(ids)) {
            throw new BizException("Failed to clear the existing draft intent tree");
        }
    }

    private String resolveParentId(String parentCode, Map<String, String> createdNodeIdsByCode) {
        if (!StringUtils.hasText(parentCode)) {
            return null;
        }
        String parentId = createdNodeIdsByCode.get(parentCode.trim());
        if (!StringUtils.hasText(parentId)) {
            throw new BizException("Template intent_tree references an unknown parent code: " + parentCode);
        }
        return parentId;
    }

    private AssistantTemplateDTO requireTemplate(String templateId) {
        AssistantTemplateDTO template = assistantTemplateRepository.findById(templateId);
        if (template == null) {
            throw new BizException("Assistant template not found: " + templateId);
        }
        return template;
    }

    private String requireUniqueCode(String code, String currentTemplateId) {
        AssistantTemplateDTO existing = assistantTemplateRepository.findByCode(code);
        if (existing != null && !existing.getId().equals(currentTemplateId)) {
            throw new BizException("Assistant template code already exists: " + code);
        }
        return code;
    }

    private String normalizeCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BizException("Template code is required");
        }
        String normalized = code.trim().toLowerCase();
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{1,63}")) {
            throw new BizException("Template code must match [a-z0-9][a-z0-9_-]{1,63}");
        }
        return normalized;
    }

    private String requireName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BizException("Template name is required");
        }
        return name.trim();
    }

    private String requireSystemPrompt(String systemPrompt) {
        if (!StringUtils.hasText(systemPrompt)) {
            throw new BizException("Template system prompt is required");
        }
        return systemPrompt.trim();
    }

    private AgentDTO.ModelType requireModel(String modelName) {
        if (!StringUtils.hasText(modelName)) {
            throw new BizException("Template model is required");
        }
        return AgentDTO.ModelType.fromModelName(modelName.trim());
    }

    private AgentDTO.ChatOptions normalizeChatOptions(AgentDTO.ChatOptions chatOptions) {
        if (chatOptions == null) {
            return AgentDTO.ChatOptions.defaultOptions();
        }
        AgentDTO.ChatOptions defaults = AgentDTO.ChatOptions.defaultOptions();
        return AgentDTO.ChatOptions.builder()
                .temperature(chatOptions.getTemperature() != null ? chatOptions.getTemperature() : defaults.getTemperature())
                .topP(chatOptions.getTopP() != null ? chatOptions.getTopP() : defaults.getTopP())
                .messageLength(chatOptions.getMessageLength() != null ? chatOptions.getMessageLength() : defaults.getMessageLength())
                .tokenBudget(chatOptions.getTokenBudget() != null ? chatOptions.getTokenBudget() : defaults.getTokenBudget())
                .build();
    }

    private List<String> normalizeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                unique.add(value.trim());
            }
        }
        return List.copyOf(unique);
    }

    private List<String> normalizeKnowledgeBaseIds(List<String> values) {
        return normalizeStringList(values);
    }

    private String normalizeNullableText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private List<AssistantTemplateDTO.IntentTreeNodeTemplateDTO> normalizeIntentTree(
            List<AssistantTemplateDTO.IntentTreeNodeTemplateDTO> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new BizException("Template intent_tree cannot be empty");
        }
        List<AssistantTemplateDTO.IntentTreeNodeTemplateDTO> normalized = new ArrayList<>();
        Set<String> codes = new LinkedHashSet<>();
        Set<String> parentCodes = new LinkedHashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            AssistantTemplateDTO.IntentTreeNodeTemplateDTO node = nodes.get(i);
            if (node == null) {
                throw new BizException("Template intent_tree cannot contain null nodes");
            }
            String code = normalizeNodeCode(node.getCode());
            if (!codes.add(code)) {
                throw new BizException("Duplicate template node code: " + code);
            }
            String parentCode = StringUtils.hasText(node.getParentCode()) ? node.getParentCode().trim() : null;
            if (parentCode != null) {
                parentCodes.add(parentCode);
            }
            IntentNodeLevel nodeLevel = node.getNodeLevel();
            if (nodeLevel == null) {
                throw new BizException("Template node level is required");
            }
            boolean isTopic = nodeLevel == IntentNodeLevel.TOPIC;
            normalized.add(AssistantTemplateDTO.IntentTreeNodeTemplateDTO.builder()
                    .code(code)
                    .parentCode(parentCode)
                    .nodeLevel(nodeLevel)
                    .name(requireName(node.getName()))
                    .description(normalizeNullableText(node.getDescription()))
                    .examples(normalizeStringList(node.getExamples()))
                    .intentKind(isTopic ? node.getIntentKind() : null)
                    .scopePolicy(isTopic ? node.getScopePolicy() : null)
                    .allowedTools(isTopic ? normalizeStringList(node.getAllowedTools()) : List.of())
                    .systemPromptOverride(isTopic ? normalizeNullableText(node.getSystemPromptOverride()) : null)
                    .bindSelectedKnowledgeBases(isTopic ? Boolean.TRUE.equals(node.getBindSelectedKnowledgeBases()) : Boolean.FALSE)
                    .enabled(node.getEnabled() == null ? Boolean.TRUE : node.getEnabled())
                    .sortOrder(node.getSortOrder() == null ? i : node.getSortOrder())
                    .build());
        }
        for (AssistantTemplateDTO.IntentTreeNodeTemplateDTO node : normalized) {
            if (node.getParentCode() == null) {
                if (node.getNodeLevel() != IntentNodeLevel.DOMAIN) {
                    throw new BizException("Root template nodes must use DOMAIN level");
                }
                continue;
            }
            if (!codes.contains(node.getParentCode())) {
                throw new BizException("Template node references a missing parent code: " + node.getParentCode());
            }
        }
        for (String parentCode : parentCodes) {
            AssistantTemplateDTO.IntentTreeNodeTemplateDTO parentNode = normalized.stream()
                    .filter(node -> parentCode.equals(node.getCode()))
                    .findFirst()
                    .orElse(null);
            if (parentNode != null && parentNode.getNodeLevel() == IntentNodeLevel.TOPIC) {
                throw new BizException("TOPIC template nodes cannot have child nodes: " + parentCode);
            }
        }
        return List.copyOf(normalized);
    }

    private String normalizeNodeCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BizException("Template node code is required");
        }
        return code.trim();
    }

    private AssistantTemplateVO toVO(AssistantTemplateDTO template) {
        return AssistantTemplateVO.builder()
                .id(template.getId())
                .code(template.getCode())
                .name(template.getName())
                .description(template.getDescription())
                .systemPrompt(template.getSystemPrompt())
                .model(template.getModel())
                .allowedTools(template.getAllowedTools())
                .chatOptions(template.getChatOptions())
                .intentTree(template.getIntentTree().stream()
                        .map(node -> AssistantTemplateVO.IntentTreeNodeTemplateVO.builder()
                                .code(node.getCode())
                                .parentCode(node.getParentCode())
                                .nodeLevel(node.getNodeLevel() == null ? null : node.getNodeLevel().name())
                                .name(node.getName())
                                .description(node.getDescription())
                                .examples(node.getExamples())
                                .intentKind(node.getIntentKind() == null ? null : node.getIntentKind().name())
                                .scopePolicy(node.getScopePolicy() == null ? null : node.getScopePolicy().name())
                                .allowedTools(node.getAllowedTools())
                                .systemPromptOverride(node.getSystemPromptOverride())
                                .bindSelectedKnowledgeBases(node.getBindSelectedKnowledgeBases())
                                .enabled(node.getEnabled())
                                .sortOrder(node.getSortOrder())
                                .build())
                        .toList())
                .builtIn(template.getBuiltIn())
                .createdAt(template.getCreatedAt())
                .updatedAt(template.getUpdatedAt())
                .build();
    }
}
