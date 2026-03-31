package com.yulong.chatagent.rag.ingestion;

/**
 * Signals that a knowledge-document ingestion attempt failed in a way that may succeed on retry.
 */
public class RetryableKnowledgeDocumentIngestionException extends RuntimeException {

    public RetryableKnowledgeDocumentIngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
