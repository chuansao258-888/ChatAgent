package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;

/**
 * Async ingestion pipeline for knowledge-base documents.
 */
public interface KnowledgeDocumentIngestionService {

    void ingest(String knowledgeBaseId, KnowledgeDocumentDTO knowledgeDocument);

    /**
     * Synchronous variant used by the MQ consumer where the caller controls ack/nack.
     */
    void ingestSync(String knowledgeBaseId, KnowledgeDocumentDTO knowledgeDocument);
}
