package com.yulong.chatagent.knowledge.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class KnowledgeDocumentVO {
    private String id;
    private String knowledgeBaseId;
    private String filename;
    private String originalFilename;
    private String mimeType;
    private Long sizeBytes;
    private String parseStatus;
    private boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
