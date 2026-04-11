package com.yulong.chatagent.rag.application;

import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.rag.SearchScopeResolver;
import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.rag.model.RetrievalHit;
import com.yulong.chatagent.rag.retrieve.KnowledgeBaseSimilaritySearcher;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Default RAG service delegating embedding and retrieval to dedicated collaborators.
 */
@Service
public class RagService {

    private final OllamaEmbeddingClient embeddingClient;
    private final KnowledgeBaseSimilaritySearcher knowledgeBaseSimilaritySearcher;
    private final SearchScopeResolver searchScopeResolver;

    public RagService(OllamaEmbeddingClient embeddingClient,
                      KnowledgeBaseSimilaritySearcher knowledgeBaseSimilaritySearcher,
                      SearchScopeResolver searchScopeResolver) {
        this.embeddingClient = embeddingClient;
        this.knowledgeBaseSimilaritySearcher = knowledgeBaseSimilaritySearcher;
        this.searchScopeResolver = searchScopeResolver;
    }

    /**
     * Produces an embedding vector for one text input.
     *
     * @param text source text
     * @return embedding vector
     */
    public float[] embed(String text) {
        return embeddingClient.embed(text);
    }

    /**
     * Performs similarity search within the files attached to one chat session.
     *
     * @param chatSessionId chat session identifier
     * @param query search query text
     * @return structured retrieval hits
     */
    public List<RetrievalHit> similaritySearchBySession(String chatSessionId, String query) {
        return searchScopeResolver.searchBySession(chatSessionId, query);
    }

    public List<RetrievalHit> similaritySearchBySession(String chatSessionId,
                                                        String query,
                                                        IntentResolution intentResolution) {
        return searchScopeResolver.searchBySession(chatSessionId, query, intentResolution);
    }

    /**
     * Performs similarity search across one or more knowledge bases.
     *
     * @param knowledgeBaseIds knowledge-base identifiers
     * @param query search query text
     * @return structured retrieval hits
     */
    public List<RetrievalHit> similaritySearchByKnowledgeBaseIds(List<String> knowledgeBaseIds, String query) {
        return knowledgeBaseSimilaritySearcher.searchByKnowledgeBaseIds(knowledgeBaseIds, query);
    }
}
