package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence entity for the chat_session_summary table.
 */
@Data
@Builder
public class ChatSessionSummary {
    private String sessionId;
    private Long lastSeqNo;
    private String summary;
    private String anchoredEntities;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
