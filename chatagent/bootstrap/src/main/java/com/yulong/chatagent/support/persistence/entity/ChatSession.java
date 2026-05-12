package com.yulong.chatagent.support.persistence.entity;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * @TableName chat_session
 */
@Data
@Builder
public class ChatSession {
    private String id;

    private String userId;

    private String agentId;

    private String title;

    // JSON string
    private String metadata;

    private Long nextTurnSeq;

    private Long lastCompletedTurnSeq;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
