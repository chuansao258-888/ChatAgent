package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.knowledge.model.request.CreateKnowledgeBaseRequest;
import com.yulong.chatagent.knowledge.model.request.UpdateKnowledgeBaseRequest;
import com.yulong.chatagent.knowledge.model.response.CreateKnowledgeBaseResponse;
import com.yulong.chatagent.knowledge.model.response.GetKnowledgeBasesResponse;

/**
 * Facade for knowledge base CRUD operations.
 */
public interface KnowledgeBaseFacadeService {

    /**
     * Lists all knowledge bases.
     *
     * @return knowledge base list response
     */
    GetKnowledgeBasesResponse getKnowledgeBases();

    /**
     * Creates a knowledge base.
     *
     * @param request create knowledge base request
     * @return created knowledge base response
     */
    CreateKnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request);

    /**
     * Deletes a knowledge base.
     *
     * @param knowledgeBaseId knowledge base identifier
     */
    void deleteKnowledgeBase(String knowledgeBaseId);

    /**
     * Updates mutable knowledge base metadata.
     *
     * @param knowledgeBaseId knowledge base identifier
     * @param request update request payload
     */
    void updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request);
}

