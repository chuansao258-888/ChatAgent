package com.yulong.chatagent.knowledge.model.request;

import lombok.Data;

@Data
public class CreateKnowledgeBaseRequest {
    private String name;
    private String description;
}
