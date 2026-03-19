package com.yulong.chatagent.rag.repository;

import com.yulong.chatagent.rag.model.KnowledgeChunk;

/**
 * Persistence port for chunk records derived from documents.
 */
public interface DocumentChunkRepository {
    /**
     * Persists one chunk.
     *
     * @param chunk chunk to save
     */
    void save(KnowledgeChunk chunk);

    /**
     * Deletes all chunks that belong to one document.
     *
     * @param documentId document identifier
     */
    void deleteByDocumentId(String documentId);
}
