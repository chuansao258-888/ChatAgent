package com.yulong.chatagent.support.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DTO describing one stored document inside a knowledge base.
 */
@Data
@Builder
public class KnowledgeDocumentDTO {
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
