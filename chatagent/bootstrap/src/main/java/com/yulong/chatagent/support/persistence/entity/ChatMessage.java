package com.yulong.chatagent.support.persistence.entity;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * @TableName chat_message
 */
@Data
@Builder
public class ChatMessage {
    private String id;

    private String sessionId;

    private String turnId;

    private String role;

    private String content;

    // JSON String
    private String metadata;

    private Long seqNo;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
