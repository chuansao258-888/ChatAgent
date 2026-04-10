package com.yulong.chatagent.support.persistence.adapter.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.port.AssistantTemplateRepository;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.AssistantTemplateDTO;
import com.yulong.chatagent.support.persistence.entity.AgentTemplate;
import com.yulong.chatagent.support.persistence.mapper.AgentTemplateMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis-backed template repository.
 */
@Repository
public class MyBatisAssistantTemplateRepository implements AssistantTemplateRepository {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<AssistantTemplateDTO.IntentTreeNodeTemplateDTO>> TEMPLATE_NODE_LIST_TYPE =
            new TypeReference<>() {
            };

    private final AgentTemplateMapper agentTemplateMapper;
    private final ObjectMapper objectMapper;

    public MyBatisAssistantTemplateRepository(AgentTemplateMapper agentTemplateMapper,
                                              ObjectMapper objectMapper) {
        this.agentTemplateMapper = agentTemplateMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AssistantTemplateDTO> findAll() {
        List<AssistantTemplateDTO> result = new ArrayList<>();
        for (AgentTemplate template : agentTemplateMapper.selectAll()) {
            result.add(toDTO(template));
        }
        return result;
    }

    @Override
    public AssistantTemplateDTO findById(String id) {
        return toDTO(agentTemplateMapper.selectById(id));
    }

    @Override
    public AssistantTemplateDTO findByCode(String code) {
        return toDTO(agentTemplateMapper.selectByCode(code));
    }

    @Override
    public boolean save(AssistantTemplateDTO template) {
        return agentTemplateMapper.insert(toEntity(template)) > 0;
    }

    @Override
    public boolean update(AssistantTemplateDTO template) {
        return agentTemplateMapper.updateById(toEntity(template)) > 0;
    }

    @Override
    public boolean deleteById(String id) {
        return agentTemplateMapper.deleteById(id) > 0;
    }

    private AssistantTemplateDTO toDTO(AgentTemplate template) {
        if (template == null) {
            return null;
        }
        try {
            return AssistantTemplateDTO.builder()
                    .id(template.getId())
                    .code(template.getCode())
                    .name(template.getName())
                    .description(template.getDescription())
                    .systemPrompt(template.getSystemPrompt())
                    .model(AgentDTO.ModelType.fromModelName(template.getModel()))
                    .allowedTools(readStringList(template.getAllowedTools()))
                    .chatOptions(readChatOptions(template.getChatOptions()))
                    .intentTree(readIntentTree(template.getIntentTree()))
                    .builtIn(template.getBuiltIn())
                    .createdAt(template.getCreatedAt())
                    .updatedAt(template.getUpdatedAt())
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize assistant template", e);
        }
    }

    private AgentTemplate toEntity(AssistantTemplateDTO dto) {
        if (dto == null) {
            return null;
        }
        try {
            return AgentTemplate.builder()
                    .id(dto.getId())
                    .code(dto.getCode())
                    .name(dto.getName())
                    .description(dto.getDescription())
                    .systemPrompt(dto.getSystemPrompt())
                    .model(dto.getModel().getModelName())
                    .allowedTools(writeValue(dto.getAllowedTools() == null ? List.of() : dto.getAllowedTools()))
                    .chatOptions(writeValue(dto.getChatOptions() == null ? AgentDTO.ChatOptions.defaultOptions() : dto.getChatOptions()))
                    .intentTree(writeValue(dto.getIntentTree() == null ? List.of() : dto.getIntentTree()))
                    .builtIn(Boolean.TRUE.equals(dto.getBuiltIn()))
                    .createdAt(dto.getCreatedAt())
                    .updatedAt(dto.getUpdatedAt())
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize assistant template", e);
        }
    }

    private List<String> readStringList(String json) throws JsonProcessingException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, STRING_LIST_TYPE);
    }

    private AgentDTO.ChatOptions readChatOptions(String json) throws JsonProcessingException {
        if (json == null || json.isBlank()) {
            return AgentDTO.ChatOptions.defaultOptions();
        }
        return objectMapper.readValue(json, AgentDTO.ChatOptions.class);
    }

    private List<AssistantTemplateDTO.IntentTreeNodeTemplateDTO> readIntentTree(String json) throws JsonProcessingException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, TEMPLATE_NODE_LIST_TYPE);
    }

    private String writeValue(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }
}
