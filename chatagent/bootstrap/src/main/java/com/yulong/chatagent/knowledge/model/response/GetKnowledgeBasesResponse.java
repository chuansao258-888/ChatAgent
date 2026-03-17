package com.yulong.chatagent.knowledge.model.response;

import com.yulong.chatagent.knowledge.model.vo.KnowledgeBaseVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetKnowledgeBasesResponse {
    private KnowledgeBaseVO[] knowledgeBases;
}


