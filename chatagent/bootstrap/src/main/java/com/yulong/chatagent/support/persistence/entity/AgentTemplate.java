package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence entity for the {@code agent_template} table.
 */
@Data
@Builder
public class AgentTemplate {
    private String id;
    private String code;
    private String name;
    private String description;
    private String systemPrompt;
    private String model;
    private String allowedTools;
    private String chatOptions;
    private String intentTree;
    private Boolean builtIn;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

