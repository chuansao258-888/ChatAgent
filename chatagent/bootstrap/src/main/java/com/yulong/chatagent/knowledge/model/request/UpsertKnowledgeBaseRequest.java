package com.yulong.chatagent.knowledge.model.request;

import lombok.Data;

/**
 * Unified payload for creating or updating a knowledge base.
 */
@Data
public class UpsertKnowledgeBaseRequest {
    private String name;
    private String description;
}
