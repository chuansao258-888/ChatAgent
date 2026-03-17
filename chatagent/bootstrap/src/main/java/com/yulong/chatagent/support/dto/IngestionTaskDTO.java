package com.yulong.chatagent.support.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class IngestionTaskDTO {
    private String id;
    private String kbId;
    private String documentId;
    private String filePath;
    private String fileType;
    private String status;
    private Integer chunkCount;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
