package com.yulong.chatagent.support.dto;

import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.IntentNodeLevel;
import com.yulong.chatagent.intent.model.ScopePolicy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for assistant templates stored in {@code agent_template}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssistantTemplateDTO {
    private String id;
    private String code;
    private String name;
    private String description;
    private String systemPrompt;
    /** Rich model type for business logic. */
    private AgentDTO.ModelType model;
    /** Raw model string mapped by MyBatis for the {@code model} column. */
    private String modelValue;
    /** Rich allowed-tools list for business logic. */
    private List<String> allowedTools;
    /** Raw JSON string mapped by MyBatis for the {@code allowed_tools} column. */
    private String allowedToolsJson;
    /** Rich chat options for business logic. */
    private AgentDTO.ChatOptions chatOptions;
    /** Raw JSON string mapped by MyBatis for the {@code chat_options} column. */
    private String chatOptionsJson;
    /** Rich intent tree for business logic. */
    private List<IntentTreeNodeTemplateDTO> intentTree;
    /** Raw JSON string mapped by MyBatis for the {@code intent_tree} column. */
    private String intentTreeJson;
    private Boolean builtIn;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntentTreeNodeTemplateDTO {
        private String code;
        private String parentCode;
        private IntentNodeLevel nodeLevel;
        private String name;
        private String description;
        private List<String> examples;
        private IntentKind intentKind;
        private ScopePolicy scopePolicy;
        private List<String> allowedTools;
        private String systemPromptOverride;
        private Boolean bindSelectedKnowledgeBases;
        private Boolean enabled;
        private Integer sortOrder;
    }
}
