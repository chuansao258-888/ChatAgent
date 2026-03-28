package com.yulong.chatagent.knowledge.model.request;

import lombok.Data;

@Data
public class SetAssistantKnowledgeBasesRequest {
    private String[] knowledgeBaseIds;
}
