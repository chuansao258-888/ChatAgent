package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence entity for the {@code knowledge_document} table.
 */
@Data
@Builder
public class KnowledgeDocument {
    private String id;
    private String knowledgeBaseId;
    private String filename;
    private String originalFilename;
    private String mimeType;
    private Long sizeBytes;
    private String storagePath;
    private String parseStatus;
    private String contentHash;
    private String failedReason;
    private LocalDateTime indexedAt;
    private Integer retryCount;
    private String metadata;
    private Boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
