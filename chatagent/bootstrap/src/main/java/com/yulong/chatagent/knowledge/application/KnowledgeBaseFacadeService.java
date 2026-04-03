package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.knowledge.model.request.CreateKnowledgeBaseRequest;
import com.yulong.chatagent.knowledge.model.request.UpdateKnowledgeBaseRequest;
import com.yulong.chatagent.knowledge.model.response.CreateKnowledgeBaseResponse;
import com.yulong.chatagent.knowledge.model.response.GetKnowledgeBaseResponse;
import com.yulong.chatagent.knowledge.model.response.GetKnowledgeBasesResponse;

public interface KnowledgeBaseFacadeService {

    GetKnowledgeBasesResponse getKnowledgeBases();

    GetKnowledgeBaseResponse getKnowledgeBase(String knowledgeBaseId);

    CreateKnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request);

    void updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request);

    void deleteKnowledgeBase(String knowledgeBaseId);
}
