package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence entity for the {@code knowledge_chunk} table.
 */
@Data
@Builder
public class KnowledgeChunk {
    private String id;
    private String knowledgeDocumentId;
    private Integer chunkIndex;
    private String content;
    private Integer tokenCount;
    private String metadata;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
