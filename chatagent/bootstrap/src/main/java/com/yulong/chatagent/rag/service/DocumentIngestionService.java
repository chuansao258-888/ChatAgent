package com.yulong.chatagent.rag.service;

/**
 * Handles chunk creation and cleanup for persisted knowledge-base documents.
 */
public interface DocumentIngestionService {
    /**
     * Parses a markdown document, produces chunks, and persists their embeddings.
     *
     * @param kbId knowledge base identifier
     * @param documentId document identifier
     * @param filePath stored document path
     * @return number of produced chunks
     */
    int ingestMarkdownDocument(String kbId, String documentId, String filePath);

    /**
     * Deletes all chunk records that belong to one document.
     *
     * @param documentId document identifier
     */
    void deleteDocumentChunks(String documentId);
}
