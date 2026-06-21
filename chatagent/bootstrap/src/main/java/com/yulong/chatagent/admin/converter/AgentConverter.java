package com.yulong.chatagent.admin.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.admin.model.request.UpsertAgentRequest;
import com.yulong.chatagent.admin.model.vo.AgentVO;
import com.yulong.chatagent.chat.routing.ChatRoutingProperties;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.persistence.entity.Agent;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * Converts agents between the persistence entity, DTO, upsert request, and admin-facing view object,
 * handling JSON serialization of allowed tools and chat options.
 */
@Component
@AllArgsConstructor
public class AgentConverter {

    private final ObjectMapper objectMapper;
    private final ChatRoutingProperties chatRoutingProperties;

    public Agent toEntity(AgentDTO agentDTO) throws JsonProcessingException {
        Assert.notNull(agentDTO, "AgentDTO cannot be null");
        Assert.notNull(agentDTO.getAllowedTools(), "Allowed tools cannot be null");
        Assert.notNull(agentDTO.getChatOptions(), "Chat options cannot be null");

        return Agent.builder()
                .id(agentDTO.getId())
                .userId(agentDTO.getUserId())
                .name(agentDTO.getName())
                .description(agentDTO.getDescription())
                .systemPrompt(agentDTO.getSystemPrompt())
                .model(configuredAgentModelName())
                .allowedTools(objectMapper.writeValueAsString(agentDTO.getAllowedTools()))
                .chatOptions(objectMapper.writeValueAsString(agentDTO.getChatOptions()))
                .activeIntentVersion(agentDTO.getActiveIntentVersion())
                .createdAt(agentDTO.getCreatedAt())
                .updatedAt(agentDTO.getUpdatedAt())
                .build();
    }

    public AgentDTO toDTO(Agent agent) throws JsonProcessingException {
        Assert.notNull(agent, "Agent cannot be null");
        Assert.notNull(agent.getAllowedTools(), "Allowed tools cannot be null");
        Assert.notNull(agent.getChatOptions(), "Chat options cannot be null");

        return AgentDTO.builder()
                .id(agent.getId())
                .userId(agent.getUserId())
                .name(agent.getName())
                .description(agent.getDescription())
                .systemPrompt(agent.getSystemPrompt())
                .model(configuredAgentModelType())
                .allowedTools(objectMapper.readValue(agent.getAllowedTools(), new TypeReference<>(){}))
                .chatOptions(objectMapper.readValue(agent.getChatOptions(), AgentDTO.ChatOptions.class))
                .activeIntentVersion(agent.getActiveIntentVersion())
                .createdAt(agent.getCreatedAt())
                .updatedAt(agent.getUpdatedAt())
                .build();
    }

    public AgentVO toVO(AgentDTO dto) {
        return AgentVO.builder()
                .id(dto.getId())
                .name(dto.getName())
                .description(dto.getDescription())
                .systemPrompt(dto.getSystemPrompt())
                .model(configuredAgentModelType())
                .allowedTools(dto.getAllowedTools())
                .chatOptions(dto.getChatOptions())
                .build();
    }

    public AgentVO toVO(Agent agent) throws JsonProcessingException {
        return toVO(toDTO(agent));
    }

    public AgentDTO toDTO(UpsertAgentRequest request) {
        Assert.notNull(request, "UpsertAgentRequest cannot be null");
        Assert.notNull(request.getAllowedTools(), "Allowed tools cannot be null");
        Assert.notNull(request.getChatOptions(), "Chat options cannot be null");

        return AgentDTO.builder()
                .name(request.getName())
                .description(request.getDescription())
                .systemPrompt(request.getSystemPrompt())
                .model(configuredAgentModelType())
                .allowedTools(request.getAllowedTools())
                .chatOptions(request.getChatOptions())
                .build();
    }

    public void updateDTOFromRequest(AgentDTO dto, UpsertAgentRequest request) {
        Assert.notNull(dto, "AgentDTO cannot be null");
        Assert.notNull(request, "UpsertAgentRequest cannot be null");

        if (request.getName() != null) {
            dto.setName(request.getName());
        }
        if (request.getDescription() != null) {
            dto.setDescription(request.getDescription());
        }
        if (request.getSystemPrompt() != null) {
            dto.setSystemPrompt(request.getSystemPrompt());
        }
        dto.setModel(configuredAgentModelType());
        if (request.getAllowedTools() != null) {
            dto.setAllowedTools(request.getAllowedTools());
        }
        if (request.getChatOptions() != null) {
            dto.setChatOptions(request.getChatOptions());
        }
    }

    private AgentDTO.ModelType configuredAgentModelType() {
        return AgentDTO.ModelType.fromModelName(configuredAgentModelName());
    }

    private String configuredAgentModelName() {
        return chatRoutingProperties.getAgentPrimaryModel();
    }
}
