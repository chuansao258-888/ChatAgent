package com.yulong.chatagent.rag.service.impl;

import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.rag.retrieve.KnowledgeChunkSimilaritySearcher;
import com.yulong.chatagent.rag.service.RagService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Default RAG service delegating embedding and retrieval to dedicated collaborators.
 */
@Service
public class RagServiceImpl implements RagService {

    private final OllamaEmbeddingClient embeddingClient;
    private final KnowledgeChunkSimilaritySearcher similaritySearcher;

    public RagServiceImpl(OllamaEmbeddingClient embeddingClient,
                          KnowledgeChunkSimilaritySearcher similaritySearcher) {
        this.embeddingClient = embeddingClient;
        this.similaritySearcher = similaritySearcher;
    }

    @Override
    public float[] embed(String text) {
        return embeddingClient.embed(text);
    }

    @Override
    public List<String> similaritySearch(String kbId, String title) {
        return similaritySearcher.search(kbId, title);
    }
}
