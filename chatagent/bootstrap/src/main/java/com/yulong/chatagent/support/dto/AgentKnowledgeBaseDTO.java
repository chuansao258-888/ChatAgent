package com.yulong.chatagent.support.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DTO representing one assistant-to-knowledge-base binding.
 */
@Data
@Builder
public class AgentKnowledgeBaseDTO {
    private String agentId;
    private String knowledgeBaseId;
    private LocalDateTime createdAt;
}
