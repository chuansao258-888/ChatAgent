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
     * Performs similarity search within one knowledge base.
     *
     * @param kbId knowledge base identifier
     * @param title search query or title text
     * @return retrieved context snippets
     */
    List<String> similaritySearch(String kbId, String title);
}
