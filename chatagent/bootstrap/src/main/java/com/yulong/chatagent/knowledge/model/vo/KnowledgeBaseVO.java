package com.yulong.chatagent.knowledge.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class KnowledgeBaseVO {
    private String id;
    private String name;
    private String description;
    private String visibility;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
