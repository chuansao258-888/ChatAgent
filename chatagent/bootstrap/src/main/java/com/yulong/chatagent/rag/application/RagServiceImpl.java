package com.yulong.chatagent.rag.application;

import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.rag.SearchScopeResolver;
import com.yulong.chatagent.rag.model.RetrievalHit;
import com.yulong.chatagent.rag.retrieve.KnowledgeBaseSimilaritySearcher;
import com.yulong.chatagent.rag.application.RagService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Default RAG service delegating embedding and retrieval to dedicated collaborators.
 */
@Service
public class RagServiceImpl implements RagService {

    private final OllamaEmbeddingClient embeddingClient;
    private final KnowledgeBaseSimilaritySearcher knowledgeBaseSimilaritySearcher;
    private final SearchScopeResolver searchScopeResolver;

    public RagServiceImpl(OllamaEmbeddingClient embeddingClient,
                          KnowledgeBaseSimilaritySearcher knowledgeBaseSimilaritySearcher,
                          SearchScopeResolver searchScopeResolver) {
        this.embeddingClient = embeddingClient;
        this.knowledgeBaseSimilaritySearcher = knowledgeBaseSimilaritySearcher;
        this.searchScopeResolver = searchScopeResolver;
    }

    @Override
    public float[] embed(String text) {
        return embeddingClient.embed(text);
    }

    @Override
    public List<RetrievalHit> similaritySearchBySession(String chatSessionId, String query) {
        return searchScopeResolver.searchBySession(chatSessionId, query);
    }

    @Override
    public List<RetrievalHit> similaritySearchBySession(String chatSessionId, String query, IntentResolution intentResolution) {
        return searchScopeResolver.searchBySession(chatSessionId, query, intentResolution);
    }

    @Override
    public List<RetrievalHit> similaritySearchByKnowledgeBaseIds(List<String> knowledgeBaseIds, String query) {
        return knowledgeBaseSimilaritySearcher.searchByKnowledgeBaseIds(knowledgeBaseIds, query);
    }
}
