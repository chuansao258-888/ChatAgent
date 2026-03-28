package com.yulong.chatagent.support.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DTO representing one administrator-managed knowledge base.
 */
@Data
@Builder
public class KnowledgeBaseDTO {
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
