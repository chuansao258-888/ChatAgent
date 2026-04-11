package com.yulong.chatagent.support.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO representing one assistant-to-knowledge-base binding.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentKnowledgeBaseDTO {
    private String agentId;
    private String knowledgeBaseId;
    private LocalDateTime createdAt;
}
