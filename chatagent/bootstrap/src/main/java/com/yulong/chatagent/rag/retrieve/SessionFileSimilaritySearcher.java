package com.yulong.chatagent.rag.retrieve;

import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.rag.vector.milvus.MilvusIndexService;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Performs hybrid retrieval over session files:
 * dense vector search + BM25 sparse search + RRF fusion + optional rerank.
 */
@Component
@Slf4j
public class SessionFileSimilaritySearcher {

    private final OllamaEmbeddingClient embeddingClient;
    private final int topK;
    private final int candidateK;
    private final int rrfK;
    private final RetrievalReranker retrievalReranker;
    private final ObjectProvider<MilvusIndexService> milvusIndexServiceProvider;

    public SessionFileSimilaritySearcher(OllamaEmbeddingClient embeddingClient,
                                         @Value("${rag.retrieval.top-k:3}") int topK,
                                         @Value("${rag.retrieval.candidate-k:12}") int candidateK,
                                         @Value("${rag.retrieval.rrf-k:60}") int rrfK,
                                         RetrievalReranker retrievalReranker,
                                         ObjectProvider<MilvusIndexService> milvusIndexServiceProvider) {
        this.embeddingClient = embeddingClient;
        this.topK = topK;
        this.candidateK = candidateK;
        this.rrfK = rrfK;
        this.retrievalReranker = retrievalReranker;
        this.milvusIndexServiceProvider = milvusIndexServiceProvider;
    }

    /**
     * Executes the full retrieval stack inside the current session-file scope.
     */
    public List<String> searchBySessionFileIds(List<String> sessionFileIds, String queryText) {
        if (sessionFileIds == null || sessionFileIds.isEmpty()) {
            return List.of();
        }

        long startTime = System.nanoTime();
        float[] embedding = embeddingClient.embed(queryText);
        MilvusIndexService milvusIndexService = milvusIndexServiceProvider.getIfAvailable();
        if (milvusIndexService != null) {
            int effectiveCandidateK = Math.max(topK, candidateK);
            List<MilvusSearchHit> denseHits = milvusIndexService.searchBySessionFileIds(sessionFileIds, embedding, effectiveCandidateK);
            List<MilvusSearchHit> bm25Hits = milvusIndexService.searchBySessionFileIdsBm25(sessionFileIds, queryText, effectiveCandidateK);
            List<MilvusSearchHit> fusedHits = fuseHits(denseHits, bm25Hits, effectiveCandidateK);
            List<MilvusSearchHit> rerankedHits = retrievalReranker.rerank(queryText, fusedHits);
            List<MilvusSearchHit> hits = rerankedHits.stream().limit(topK).toList();
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            log.info("Hybrid similarity search by session files completed: traceId={}, fileCount={}, topK={}, candidateK={}, denseHits={}, bm25Hits={}, fusedHits={}, rerankedHits={}, durationMs={}",
                    TraceContext.getTraceId(), sessionFileIds.size(), topK, effectiveCandidateK, denseHits.size(), bm25Hits.size(), fusedHits.size(), hits.size(), durationMs);
            return hits.stream().map(this::renderHitContent).toList();
        }

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        log.warn("Similarity search skipped: traceId={}, fileCount={}, topK={}, durationMs={}, reason=milvus-disabled",
                TraceContext.getTraceId(), sessionFileIds.size(), topK, durationMs);
        return List.of();
    }

    /**
     * Fuses dense and sparse candidate lists by chunk id using Reciprocal Rank Fusion.
     */
    private List<MilvusSearchHit> fuseHits(List<MilvusSearchHit> denseHits, List<MilvusSearchHit> bm25Hits, int limit) {
        Map<String, RankedHit> fused = new LinkedHashMap<>();
        addHitsByRrf(fused, denseHits);
        addHitsByRrf(fused, bm25Hits);
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(RankedHit::score).reversed())
                .limit(limit)
                .map(RankedHit::hit)
                .toList();
    }

    /**
     * Adds one ranked list into the fused map. RRF uses rank positions instead of raw scores so
     * dense cosine scores and BM25 scores never need manual normalization.
     */
    private void addHitsByRrf(Map<String, RankedHit> fused, List<MilvusSearchHit> hits) {
        for (int i = 0; i < hits.size(); i++) {
            MilvusSearchHit hit = hits.get(i);
            if (!StringUtils.hasText(hit.chunkId())) {
                continue;
            }
            double rrfScore = 1.0d / (rrfK + i + 1);
            fused.compute(hit.chunkId(), (chunkId, existing) -> {
                if (existing == null) {
                    return new RankedHit(hit, rrfScore);
                }
                return new RankedHit(existing.hit(), existing.score() + rrfScore);
            });
        }
    }

    /**
     * Normalizes retrieval hits into a stable prompt shape for the agent/runtime layer.
     */
    private String renderHitContent(MilvusSearchHit hit) {
        if (StringUtils.hasText(hit.contextText())) {
            return "Chunk Context:\n" + hit.contextText() + "\n\nChunk Content:\n" + hit.content();
        }
        String content = StringUtils.hasText(hit.content()) ? hit.content() : hit.retrievalText();
        if (!StringUtils.hasText(content)) {
            return "";
        }
        return "Chunk Content:\n" + content;
    }

    private record RankedHit(MilvusSearchHit hit, double score) {
    }
}
