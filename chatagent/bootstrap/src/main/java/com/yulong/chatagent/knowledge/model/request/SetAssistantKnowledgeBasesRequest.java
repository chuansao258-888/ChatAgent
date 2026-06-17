package com.yulong.chatagent.knowledge.model.request;

import lombok.Data;

/** Request body setting the set of knowledge bases bound to the assistant. */
@Data
public class SetAssistantKnowledgeBasesRequest {
    private String[] knowledgeBaseIds;
}
