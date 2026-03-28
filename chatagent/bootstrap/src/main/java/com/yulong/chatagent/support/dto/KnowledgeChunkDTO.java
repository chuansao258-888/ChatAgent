package com.yulong.chatagent.support.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DTO for one parsed retrieval chunk derived from a knowledge-base document.
 */
@Data
@Builder
public class KnowledgeChunkDTO {
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
