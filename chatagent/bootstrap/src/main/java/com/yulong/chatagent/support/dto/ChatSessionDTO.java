package com.yulong.chatagent.support.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DTO representing a chat session: ownership, associated agent, title, turn-sequence pointers,
 * and extensible metadata.
 */
@Data
@Builder
public class ChatSessionDTO {
    private String id;

    private String userId;

    private String agentId;

    private String title;

    private MetaData metadata;

    private Long nextTurnSeq;

    private Long lastCompletedTurnSeq;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * Extensible session metadata bag (currently empty).
     */
    @Data
    public static class MetaData {
    }
}
