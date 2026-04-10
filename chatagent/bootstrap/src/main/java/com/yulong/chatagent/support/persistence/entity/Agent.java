package com.yulong.chatagent.support.persistence.entity;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * @TableName agent
 */
@Data
@Builder
public class Agent {
    private String id;

    private String userId;

    private String name;

    private String description;

    private String systemPrompt;

    private String model;

    // JSON String
    private String allowedTools;

    // JSON String
    private String chatOptions;

    private Integer activeIntentVersion;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
