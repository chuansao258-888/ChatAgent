package com.yulong.chatagent.support.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO representing one parsed chunk derived from a chat-session file.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
