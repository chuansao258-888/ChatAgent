package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence entity for the {@code knowledge_document_enhancement} table.
 */
@Data
@Builder
public class KnowledgeDocumentEnhancement {
    private String knowledgeDocumentId;
    private String enhancerCacheKey;
    private String keywords;
    private String questions;
    private String metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
