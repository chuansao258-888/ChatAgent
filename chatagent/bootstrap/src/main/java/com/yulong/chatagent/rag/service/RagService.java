package com.yulong.chatagent.rag.service;

import java.util.List;

/**
 * Core retrieval-augmented generation contract.
 */
public interface RagService {
    /**
     * Produces an embedding vector for one text input.
     *
     * @param text source text
     * @return embedding vector
     */
    float[] embed(String text);

    /**
     * Performs similarity search within the files attached to one chat session.
     *
     * @param chatSessionId chat session identifier
     * @param query search query text
     * @return retrieved context snippets
     */
    List<String> similaritySearchBySession(String chatSessionId, String query);
}
