package com.yulong.chatagent.support.persistence.adapter.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.port.AssistantTemplateRepository;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.AssistantTemplateDTO;
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
        for (AssistantTemplateDTO dto : agentTemplateMapper.selectAll()) {
            populateRichFields(dto);
            result.add(dto);
        }
        return result;
    }

    @Override
    public AssistantTemplateDTO findById(String id) {
        AssistantTemplateDTO dto = agentTemplateMapper.selectById(id);
        if (dto != null) {
            populateRichFields(dto);
        }
        return dto;
    }

    @Override
    public AssistantTemplateDTO findByCode(String code) {
        AssistantTemplateDTO dto = agentTemplateMapper.selectByCode(code);
        if (dto != null) {
            populateRichFields(dto);
        }
        return dto;
    }

    @Override
    public boolean save(AssistantTemplateDTO template) {
        populateJsonFields(template);
        return agentTemplateMapper.insert(template) > 0;
    }

    @Override
    public boolean update(AssistantTemplateDTO template) {
        populateJsonFields(template);
        return agentTemplateMapper.updateById(template) > 0;
    }

    @Override
    public boolean deleteById(String id) {
        return agentTemplateMapper.deleteById(id) > 0;
    }

    private void populateRichFields(AssistantTemplateDTO dto) {
        try {
            dto.setModel(dto.getModelValue() != null
                    ? AgentDTO.ModelType.fromModelName(dto.getModelValue())
                    : null);
            dto.setAllowedTools(readStringList(dto.getAllowedToolsJson()));
            dto.setChatOptions(readChatOptions(dto.getChatOptionsJson()));
            dto.setIntentTree(readIntentTree(dto.getIntentTreeJson()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize assistant template", e);
        }
    }

    private void populateJsonFields(AssistantTemplateDTO dto) {
        try {
            dto.setModelValue(dto.getModel() != null ? dto.getModel().getModelName() : null);
            dto.setAllowedToolsJson(writeValue(dto.getAllowedTools() == null ? List.of() : dto.getAllowedTools()));
            dto.setChatOptionsJson(writeValue(dto.getChatOptions() == null ? AgentDTO.ChatOptions.defaultOptions() : dto.getChatOptions()));
            dto.setIntentTreeJson(writeValue(dto.getIntentTree() == null ? List.of() : dto.getIntentTree()));
            dto.setBuiltIn(Boolean.TRUE.equals(dto.getBuiltIn()));
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
