package com.yulong.chatagent.rag.service;

public interface DocumentIngestionService {
    int ingestMarkdownDocument(String kbId, String documentId, String filePath);

    void deleteDocumentChunks(String documentId);
}
