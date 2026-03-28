package com.yulong.chatagent.admin.model.vo;

import com.yulong.chatagent.support.dto.AgentDTO;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read model exposed to the admin UI for assistant templates.
 */
@Data
@Builder
public class AssistantTemplateVO {
    private String id;
    private String code;
    private String name;
    private String description;
    private String systemPrompt;
    private AgentDTO.ModelType model;
    private List<String> allowedTools;
    private AgentDTO.ChatOptions chatOptions;
    private List<IntentTreeNodeTemplateVO> intentTree;
    private Boolean builtIn;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @Builder
    public static class IntentTreeNodeTemplateVO {
        private String code;
        private String parentCode;
        private String nodeLevel;
        private String name;
        private String description;
        private List<String> examples;
        private String intentKind;
        private String scopePolicy;
        private List<String> allowedTools;
        private String systemPromptOverride;
        private Boolean bindSelectedKnowledgeBases;
        private Boolean enabled;
        private Integer sortOrder;
    }
}

