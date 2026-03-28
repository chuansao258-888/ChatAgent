package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;

/**
 * Async ingestion pipeline for knowledge-base documents.
 */
public interface KnowledgeDocumentIngestionService {

    void ingest(String knowledgeBaseId, KnowledgeDocumentDTO knowledgeDocument);
}
