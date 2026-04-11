package com.yulong.chatagent.support.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO representing one intent-node to knowledge-base binding.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentKnowledgeBaseDTO {
    private String id;
    private String intentNodeId;
    private String knowledgeBaseId;
    private LocalDateTime createdAt;
}

