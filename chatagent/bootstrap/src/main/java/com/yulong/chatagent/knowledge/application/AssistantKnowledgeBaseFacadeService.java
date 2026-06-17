package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.knowledge.model.request.SetAssistantKnowledgeBasesRequest;
import com.yulong.chatagent.knowledge.model.vo.KnowledgeBaseVO;

/** Operations to get and set the knowledge bases bound to the active assistant. */
public interface AssistantKnowledgeBaseFacadeService {

    KnowledgeBaseVO[] getAssistantKnowledgeBases();

    void setAssistantKnowledgeBases(SetAssistantKnowledgeBasesRequest request);
}
