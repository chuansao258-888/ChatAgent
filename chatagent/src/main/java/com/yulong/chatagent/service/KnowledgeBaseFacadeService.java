package com.yulong.chatagent.service;

import com.yulong.chatagent.model.request.CreateKnowledgeBaseRequest;
import com.yulong.chatagent.model.request.UpdateKnowledgeBaseRequest;
import com.yulong.chatagent.model.response.CreateKnowledgeBaseResponse;
import com.yulong.chatagent.model.response.GetKnowledgeBasesResponse;

public interface KnowledgeBaseFacadeService {
    GetKnowledgeBasesResponse getKnowledgeBases();

    CreateKnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request);

    void deleteKnowledgeBase(String knowledgeBaseId);

    void updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request);
}

