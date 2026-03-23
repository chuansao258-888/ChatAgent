package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence entity for files owned directly by one chat session.
 */
@Data
@Builder
public class ChatSessionFile {
    private String id;
    private String sessionId;
    private String filename;
    private String originalFilename;
    private String mimeType;
    private Long sizeBytes;
    private String storagePath;
    private String status;
    private String parseStatus;
    private String metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
