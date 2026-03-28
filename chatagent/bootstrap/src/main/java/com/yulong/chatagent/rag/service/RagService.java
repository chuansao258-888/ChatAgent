package com.yulong.chatagent.rag.service;

import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.rag.model.RetrievalHit;

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
     * @return structured retrieval hits
     */
    List<RetrievalHit> similaritySearchBySession(String chatSessionId, String query);

    List<RetrievalHit> similaritySearchBySession(String chatSessionId, String query, IntentResolution intentResolution);

    /**
     * Performs similarity search across one or more knowledge bases.
     *
     * @param knowledgeBaseIds knowledge-base identifiers
     * @param query search query text
     * @return structured retrieval hits
     */
    List<RetrievalHit> similaritySearchByKnowledgeBaseIds(List<String> knowledgeBaseIds, String query);
}
