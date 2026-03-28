package com.yulong.chatagent.support.dto;

import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.IntentNodeLevel;
import com.yulong.chatagent.intent.model.ScopePolicy;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for assistant templates stored in {@code agent_template}.
 */
@Data
@Builder
public class AssistantTemplateDTO {
    private String id;
    private String code;
    private String name;
    private String description;
    private String systemPrompt;
    private AgentDTO.ModelType model;
    private List<String> allowedTools;
    private AgentDTO.ChatOptions chatOptions;
    private List<IntentTreeNodeTemplateDTO> intentTree;
    private Boolean builtIn;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @Builder
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
