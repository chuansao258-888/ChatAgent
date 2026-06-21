package com.yulong.chatagent.admin.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.yulong.chatagent.admin.model.request.UpsertAgentRequest;
import com.yulong.chatagent.chat.routing.ChatRoutingProperties;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.persistence.entity.Agent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentConverterTest {

    private final ChatRoutingProperties routingProperties = new ChatRoutingProperties();
    private final AgentConverter converter = new AgentConverter(
            new ObjectMapper().registerModule(new ParameterNamesModule()),
            routingProperties);

    @Test
    void shouldNormalizeEntityModelToConfiguredAgentPrimaryOnWrite() throws Exception {
        AgentDTO dto = AgentDTO.builder()
                .id("agent-1")
                .userId("user-1")
                .name("Assistant")
                .model(AgentDTO.ModelType.DEEPSEEK_V4_FLASH)
                .allowedTools(List.of("SearchTool"))
                .chatOptions(AgentDTO.ChatOptions.defaultOptions())
                .build();

        Agent entity = converter.toEntity(dto);

        assertThat(entity.getModel()).isEqualTo("glm-5.2");
    }

    @Test
    void shouldExposeConfiguredAgentPrimaryWhenReadingLegacyUnknownModel() throws Exception {
        Agent legacy = Agent.builder()
                .id("agent-1")
                .userId("user-1")
                .name("Assistant")
                .model("legacy-model")
                .allowedTools("[\"SearchTool\"]")
                .chatOptions("{\"messageLength\":12,\"tokenBudget\":4000}")
                .build();

        AgentDTO dto = converter.toDTO(legacy);

        assertThat(dto.getModel()).isEqualTo(AgentDTO.ModelType.GLM_5_2);
    }

    @Test
    void shouldIgnoreRequestModelAndUseConfiguredAgentPrimary() {
        UpsertAgentRequest request = new UpsertAgentRequest();
        request.setName("Assistant");
        request.setModel("unsupported-request-model");
        request.setAllowedTools(List.of("SearchTool"));
        request.setChatOptions(AgentDTO.ChatOptions.defaultOptions());

        AgentDTO dto = converter.toDTO(request);

        assertThat(dto.getModel()).isEqualTo(AgentDTO.ModelType.GLM_5_2);
    }

    @Test
    void shouldNormalizeModelDuringPartialUpdate() {
        AgentDTO dto = AgentDTO.builder()
                .name("Old")
                .model(AgentDTO.ModelType.DEEPSEEK_V4_FLASH)
                .allowedTools(List.of("SearchTool"))
                .chatOptions(AgentDTO.ChatOptions.defaultOptions())
                .build();
        UpsertAgentRequest request = new UpsertAgentRequest();
        request.setName("New");
        request.setModel("unsupported-request-model");

        converter.updateDTOFromRequest(dto, request);

        assertThat(dto.getName()).isEqualTo("New");
        assertThat(dto.getModel()).isEqualTo(AgentDTO.ModelType.GLM_5_2);
    }
}
