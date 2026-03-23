package com.yulong.chatagent.rag.service.impl;

import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.rag.retrieve.SessionFileSimilaritySearcher;
import com.yulong.chatagent.rag.service.RagService;
import com.yulong.chatagent.file.port.ChatSessionFileRepository;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Default RAG service delegating embedding and retrieval to dedicated collaborators.
 */
@Service
public class RagServiceImpl implements RagService {

    private final OllamaEmbeddingClient embeddingClient;
    private final SessionFileSimilaritySearcher similaritySearcher;
    private final ChatSessionFileRepository chatSessionFileRepository;

    public RagServiceImpl(OllamaEmbeddingClient embeddingClient,
                          SessionFileSimilaritySearcher similaritySearcher,
                          ChatSessionFileRepository chatSessionFileRepository) {
        this.embeddingClient = embeddingClient;
        this.similaritySearcher = similaritySearcher;
        this.chatSessionFileRepository = chatSessionFileRepository;
    }

    @Override
    public float[] embed(String text) {
        return embeddingClient.embed(text);
    }

    @Override
    public List<String> similaritySearchBySession(String chatSessionId, String query) {
        List<String> sessionFileIds = new ArrayList<>();
        for (ChatSessionFileDTO relation : chatSessionFileRepository.findBySessionId(chatSessionId)) {
            if (relation.getId() != null && !relation.getId().isBlank()) {
                sessionFileIds.add(relation.getId());
            }
        }
        return similaritySearcher.searchBySessionFileIds(sessionFileIds, query);
    }
}
