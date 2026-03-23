package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.support.dto.ChatSessionFileDTO;

/**
 * Entry point for asynchronous session-file ingestion.
 */
public interface FileIngestionService {

    /**
     * Ingests one uploaded session file into chunk storage and the retrieval index.
     */
    void ingest(String sessionId, ChatSessionFileDTO sessionFile);
}
