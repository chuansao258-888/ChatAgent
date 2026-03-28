package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence entity for the intent_knowledge_base table.
 */
@Data
@Builder
public class IntentKnowledgeBase {
    private String id;
    private String intentNodeId;
    private String knowledgeBaseId;
    private LocalDateTime createdAt;
}

