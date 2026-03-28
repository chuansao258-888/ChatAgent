package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence entity for the intent_node table.
 */
@Data
@Builder
public class IntentNode {
    private String id;
    private String agentId;
    private String parentId;
    private Integer version;
    private String status;
    private String nodeLevel;
    private String name;
    private String description;
    private String examples;
    private String intentKind;
    private String scopePolicy;
    private String allowedTools;
    private String systemPromptOverride;
    private Boolean enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

