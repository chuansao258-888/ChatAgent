package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence entity mapped to chunked file content used by retrieval.
 */
@Data
@Builder
public class FileChunk {
    private String id;
    private String sessionFileId;
    private Integer chunkIndex;
    private String content;
    private Integer tokenCount;
    private String metadata;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
