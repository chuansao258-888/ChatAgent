package com.yulong.chatagent.support.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DTO describing one file stored directly under a chat session.
 */
@Data
@Builder
public class ChatSessionFileDTO {
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
