package com.yulong.chatagent.support.dto;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO carrying an agent's configuration between layers: identity, system prompt, allowed tools,
 * selected model, chat-generation options, and the active intent version.
 */
@Data
@Builder
public class AgentDTO {
    private String id;

    private String userId;

    private String name;

    private String description;

    private String systemPrompt;

    private ModelType model;

    private List<String> allowedTools;

    private ChatOptions chatOptions;

    private Integer activeIntentVersion;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * Chat models an agent can use, each mapped to its routing client key.
     */
    @Getter
    @AllArgsConstructor
    public enum ModelType {
        DEEPSEEK_V4_FLASH("deepseek-v4-flash"),
        DEEPSEEK_V4_PRO("deepseek-v4-pro"),
        GLM_5_2("glm-5.2"),
        GLM_4_7("glm-4.7"),
        /** Legacy values are kept so old request/DB payloads can still be parsed during migration. */
        DEEPSEEK_CHAT("deepseek-chat"),
        DEEPSEEK_REASONER("deepseek-reasoner"),
        GLM_4_6("glm-4.6"),
        GLM_5_1("glm-5.1");

        @JsonValue
        private final String modelName;

        public static ModelType fromModelName(String modelName) {
            for (ModelType type : ModelType.values()) {
                if (type.modelName.equals(modelName)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown model type: " + modelName);
        }
    }

    /**
     * Generation parameters for an agent, with built-in sensible defaults via {@link #defaultOptions()}.
     */
    @Data
    @AllArgsConstructor
    @Builder
    public static class ChatOptions {
        private Double temperature;
        private Double topP;
        private Integer messageLength; // Chat message window length
        private Integer tokenBudget; // L1 window token budget

        private static final Double DEFAULT_TEMPERATURE = 0.7;
        private static final Double DEFAULT_TOP_P = 1.0;
        private static final Integer DEFAULT_MESSAGE_LENGTH = 120;
        private static final Integer DEFAULT_TOKEN_BUDGET = 256000;

        public static ChatOptions defaultOptions() {
            return ChatOptions.builder()
                    .temperature(DEFAULT_TEMPERATURE)
                    .topP(DEFAULT_TOP_P)
                    .messageLength(DEFAULT_MESSAGE_LENGTH)
                    .tokenBudget(DEFAULT_TOKEN_BUDGET)
                    .build();
        }
    }
}
