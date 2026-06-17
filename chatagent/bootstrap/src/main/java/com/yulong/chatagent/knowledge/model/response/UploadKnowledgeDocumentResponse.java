package com.yulong.chatagent.knowledge.model.response;

import lombok.Builder;
import lombok.Data;

/** Response identifying a newly uploaded or replaced knowledge document. */
@Data
@Builder
public class UploadKnowledgeDocumentResponse {
    private String knowledgeBaseId;
    private String documentId;
}
