package com.yulong.chatagent.support.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DTO representing one intent-node to knowledge-base binding.
 */
@Data
@Builder
public class IntentKnowledgeBaseDTO {
    private String id;
    private String intentNodeId;
    private String knowledgeBaseId;
    private LocalDateTime createdAt;
}

