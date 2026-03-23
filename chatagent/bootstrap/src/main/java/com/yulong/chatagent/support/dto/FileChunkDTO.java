package com.yulong.chatagent.support.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DTO representing one parsed chunk derived from a chat-session file.
 */
@Data
@Builder
public class FileChunkDTO {
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
