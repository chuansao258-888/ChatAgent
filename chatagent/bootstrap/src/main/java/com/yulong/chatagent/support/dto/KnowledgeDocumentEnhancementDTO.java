package com.yulong.chatagent.support.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO describing document-level enhancement signals persisted for rerank hot paths.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentEnhancementDTO {
    private String knowledgeDocumentId;
    private String enhancerCacheKey;
    private List<String> keywords;
    private List<String> questions;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
