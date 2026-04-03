package com.yulong.chatagent.knowledge.port;

import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;

import java.util.List;

/**
 * Persistence port for knowledge-base document metadata.
 */
public interface KnowledgeDocumentRepository {

    List<KnowledgeDocumentDTO> findByKnowledgeBaseId(String knowledgeBaseId);

    KnowledgeDocumentDTO findById(String id);

    boolean save(KnowledgeDocumentDTO knowledgeDocument);

    boolean update(KnowledgeDocumentDTO knowledgeDocument);

    boolean deleteById(String id);
}
