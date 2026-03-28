package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence entity for assistant-to-knowledge-base bindings.
 */
@Data
@Builder
public class AgentKnowledgeBase {
    private String agentId;
    private String knowledgeBaseId;
    private LocalDateTime createdAt;
}
