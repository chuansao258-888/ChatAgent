package com.yulong.chatagent.knowledge.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KnowledgeBaseVO {
    private String id;
    private String name;
    private String description;
}

