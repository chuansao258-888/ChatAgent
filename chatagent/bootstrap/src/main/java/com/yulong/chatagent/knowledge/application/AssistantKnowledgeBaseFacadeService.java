package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.knowledge.model.request.SetAssistantKnowledgeBasesRequest;
import com.yulong.chatagent.knowledge.model.response.GetAssistantKnowledgeBasesResponse;

public interface AssistantKnowledgeBaseFacadeService {

    GetAssistantKnowledgeBasesResponse getAssistantKnowledgeBases();

    void setAssistantKnowledgeBases(SetAssistantKnowledgeBasesRequest request);
}
