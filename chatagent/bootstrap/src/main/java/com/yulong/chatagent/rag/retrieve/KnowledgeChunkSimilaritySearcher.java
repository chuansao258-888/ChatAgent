package com.yulong.chatagent.rag.retrieve;

import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.rag.repository.KnowledgeChunkSearchRepository;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class KnowledgeChunkSimilaritySearcher {

    private final OllamaEmbeddingClient embeddingClient;
    private final KnowledgeChunkSearchRepository knowledgeChunkSearchRepository;
    private final PgVectorFormatter pgVectorFormatter;
    private final int topK;

    public KnowledgeChunkSimilaritySearcher(OllamaEmbeddingClient embeddingClient,
                                            KnowledgeChunkSearchRepository knowledgeChunkSearchRepository,
                                            PgVectorFormatter pgVectorFormatter,
                                            @Value("${rag.retrieval.top-k:3}") int topK) {
        this.embeddingClient = embeddingClient;
        this.knowledgeChunkSearchRepository = knowledgeChunkSearchRepository;
        this.pgVectorFormatter = pgVectorFormatter;
        this.topK = topK;
    }

    public List<String> search(String kbId, String queryText) {
        long startTime = System.nanoTime();
        float[] embedding = embeddingClient.embed(queryText);
        String queryEmbedding = pgVectorFormatter.format(embedding);
        List<String> results = knowledgeChunkSearchRepository.similaritySearch(kbId, queryEmbedding, topK);
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        log.info("Similarity search completed: traceId={}, kbId={}, topK={}, resultCount={}, durationMs={}",
                TraceContext.getTraceId(), kbId, topK, results.size(), durationMs);
        return results;
    }
}
