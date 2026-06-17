package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.knowledge.model.request.UpsertKnowledgeBaseRequest;
import com.yulong.chatagent.knowledge.model.vo.KnowledgeBaseVO;

/** CRUD operations for knowledge bases. */
public interface KnowledgeBaseFacadeService {

    KnowledgeBaseVO[] getKnowledgeBases();

    KnowledgeBaseVO getKnowledgeBase(String knowledgeBaseId);

    String createKnowledgeBase(UpsertKnowledgeBaseRequest request);

    void updateKnowledgeBase(String knowledgeBaseId, UpsertKnowledgeBaseRequest request);

    void deleteKnowledgeBase(String knowledgeBaseId);
}
