package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.knowledge.model.request.SetAssistantKnowledgeBasesRequest;
import com.yulong.chatagent.knowledge.model.vo.KnowledgeBaseVO;

public interface AssistantKnowledgeBaseFacadeService {

    KnowledgeBaseVO[] getAssistantKnowledgeBases();

    void setAssistantKnowledgeBases(SetAssistantKnowledgeBasesRequest request);
}
