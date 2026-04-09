package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence entity mapped to {@code t_chat_turn_metric}.
 */
@Data
@Builder
public class ChatTurnMetric {

    private String id;

    private String sessionId;

    private String userId;

    private String turnId;

    private String agentId;

    private String status;

    private String errorType;

    private Long durationMs;

    private Boolean knowledgeHit;

    private LocalDateTime createdAt;
}
