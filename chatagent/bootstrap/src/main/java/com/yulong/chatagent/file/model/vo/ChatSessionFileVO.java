package com.yulong.chatagent.file.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * View object representing one file stored under a chat session.
 */
@Data
@Builder
public class ChatSessionFileVO {
    private String id;
    private String filename;
    private String originalFilename;
    private String mimeType;
    private Long sizeBytes;
    private String status;
    private String parseStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
