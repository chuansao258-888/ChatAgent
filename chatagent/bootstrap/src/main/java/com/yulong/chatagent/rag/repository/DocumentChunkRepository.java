package com.yulong.chatagent.rag.repository;

import com.yulong.chatagent.rag.model.KnowledgeChunk;

public interface DocumentChunkRepository {
    void save(KnowledgeChunk chunk);

    void deleteByDocumentId(String documentId);
}
