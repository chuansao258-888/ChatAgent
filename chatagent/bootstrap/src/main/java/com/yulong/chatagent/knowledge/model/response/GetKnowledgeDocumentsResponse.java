package com.yulong.chatagent.knowledge.model.response;

import com.yulong.chatagent.knowledge.model.vo.KnowledgeDocumentVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetKnowledgeDocumentsResponse {
    private KnowledgeDocumentVO[] documents;
}
