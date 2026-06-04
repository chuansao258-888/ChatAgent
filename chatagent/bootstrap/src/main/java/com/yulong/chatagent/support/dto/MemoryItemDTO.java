package com.yulong.chatagent.support.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO representing one persisted long-term memory item for a user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryItemDTO {
    private String id;
    private String userId;
    private String type;
    private String content;
    private List<String> tags;
    /** Raw JSON string mapped by MyBatis for the {@code tags} column. */
    private String tagsJson;
    private Map<String, Object> source;
    /** Raw JSON string mapped by MyBatis for the {@code source} column. */
    private String sourceJson;
    private String contentHash;
    private String status;
    private String indexStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
