package com.yulong.chatagent.knowledge.port;

import com.yulong.chatagent.support.dto.KnowledgeDocumentEnhancementDTO;

import java.util.List;

/**
 * Persistence port for document-level enhancement signals used in rerank hot paths.
 */
public interface KnowledgeDocumentEnhancementRepository {

    KnowledgeDocumentEnhancementDTO findByKnowledgeDocumentId(String knowledgeDocumentId);

    List<KnowledgeDocumentEnhancementDTO> findByKnowledgeDocumentIds(List<String> knowledgeDocumentIds);

    boolean saveOrUpdate(KnowledgeDocumentEnhancementDTO enhancement);

    boolean deleteByKnowledgeDocumentId(String knowledgeDocumentId);
}
