package com.yulong.chatagent.knowledge.port;

import com.yulong.chatagent.support.dto.KnowledgeChunkDTO;

import java.util.List;

/**
 * Persistence port for chunks derived from knowledge-base documents.
 */
public interface KnowledgeChunkRepository {

    List<KnowledgeChunkDTO> findByKnowledgeDocumentId(String knowledgeDocumentId);

    void saveAll(List<KnowledgeChunkDTO> chunks);

    void deleteByKnowledgeDocumentId(String knowledgeDocumentId);
}
