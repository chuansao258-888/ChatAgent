package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import com.yulong.chatagent.rag.model.KnowledgeChunk;
import com.yulong.chatagent.rag.repository.DocumentChunkRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class KnowledgeChunkIndexer {

    private final OllamaEmbeddingClient embeddingClient;
    private final DocumentChunkRepository documentChunkRepository;

    public KnowledgeChunkIndexer(OllamaEmbeddingClient embeddingClient,
                                 DocumentChunkRepository documentChunkRepository) {
        this.embeddingClient = embeddingClient;
        this.documentChunkRepository = documentChunkRepository;
    }

    public int index(String kbId, String documentId, List<KnowledgeChunkDraft> drafts) {
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (KnowledgeChunkDraft draft : drafts) {
            float[] embedding = embeddingClient.embed(draft.embeddingText());
            documentChunkRepository.save(new KnowledgeChunk(
                    kbId,
                    documentId,
                    draft.content(),
                    draft.metadata(),
                    embedding,
                    now,
                    now
            ));
            count++;
        }
        return count;
    }
}
