package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence entity for the {@code knowledge_base} table.
 */
@Data
@Builder
public class KnowledgeBase {
    private String id;
    private String createdBy;
    private String name;
    private String description;
    private String visibility;
    private String status;
    private String metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
