package com.yulong.chatagent.knowledge.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UploadKnowledgeDocumentResponse {
    private String knowledgeBaseId;
    private String documentId;
}
