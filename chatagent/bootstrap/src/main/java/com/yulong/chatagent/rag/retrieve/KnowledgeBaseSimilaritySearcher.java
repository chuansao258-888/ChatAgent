package com.yulong.chatagent.rag.retrieve;

import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.rag.model.RetrievalHit;
import com.yulong.chatagent.rag.vector.milvus.KnowledgeBaseMilvusIndexService;
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
 * Performs hybrid retrieval over administrator-managed knowledge bases.
 */
@Component
@Slf4j
public class KnowledgeBaseSimilaritySearcher {

    private final int topK;
    private final int candidateK;
    private final int rrfK;
    private final KnowledgeDocumentSignalService knowledgeDocumentSignalService;
    private final RetrievalReranker retrievalReranker;
    private final ObjectProvider<KnowledgeBaseMilvusIndexService> knowledgeBaseMilvusIndexServiceProvider;
    private final com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient embeddingClient;

    public KnowledgeBaseSimilaritySearcher(com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient embeddingClient,
                                           @Value("${rag.retrieval.top-k:3}") int topK,
                                           @Value("${rag.retrieval.candidate-k:12}") int candidateK,
                                           @Value("${rag.retrieval.rrf-k:60}") int rrfK,
                                           KnowledgeDocumentSignalService knowledgeDocumentSignalService,
                                           RetrievalReranker retrievalReranker,
                                           ObjectProvider<KnowledgeBaseMilvusIndexService> knowledgeBaseMilvusIndexServiceProvider) {
        this.embeddingClient = embeddingClient;
        this.topK = topK;
        this.candidateK = candidateK;
        this.rrfK = rrfK;
        this.knowledgeDocumentSignalService = knowledgeDocumentSignalService;
        this.retrievalReranker = retrievalReranker;
        this.knowledgeBaseMilvusIndexServiceProvider = knowledgeBaseMilvusIndexServiceProvider;
    }

    public List<RetrievalHit> searchByKnowledgeBaseIds(List<String> knowledgeBaseIds, String queryText) {
        List<MilvusSearchHit> candidates = searchCandidateHitsByKnowledgeBaseIds(knowledgeBaseIds, queryText);
        List<MilvusSearchHit> rerankedHits = retrievalReranker.rerank(
                queryText,
                knowledgeDocumentSignalService.attachSignals(candidates)
        );
        return rerankedHits.stream()
                .limit(topK)
                .map(this::toRetrievalHit)
                .toList();
    }

    /**
     * Returns fused dense + sparse candidates before reranking so the caller can combine them with
     * other retrieval scopes.
     */
    public List<MilvusSearchHit> searchCandidateHitsByKnowledgeBaseIds(List<String> knowledgeBaseIds, String queryText) {
        return searchRankedCandidateHitsByKnowledgeBaseIds(knowledgeBaseIds, queryText).stream()
                .map(RankedCandidateHit::hit)
                .toList();
    }

    /**
     * Returns fused candidates together with the raw dense/BM25 rank signals used to compute RRF.
     */
    public List<RankedCandidateHit> searchRankedCandidateHitsByKnowledgeBaseIds(List<String> knowledgeBaseIds, String queryText) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }

        long startTime = System.nanoTime();
        float[] embedding = embeddingClient.embed(queryText);
        KnowledgeBaseMilvusIndexService indexService = knowledgeBaseMilvusIndexServiceProvider.getIfAvailable();
        if (indexService != null) {
            int effectiveCandidateK = Math.max(topK, candidateK);
            List<MilvusSearchHit> denseHits = indexService.searchByKnowledgeBaseIds(knowledgeBaseIds, embedding, effectiveCandidateK);
            List<MilvusSearchHit> bm25Hits = indexService.searchByKnowledgeBaseIdsBm25(knowledgeBaseIds, queryText, effectiveCandidateK);
            List<RankedCandidateHit> fusedHits = fuseRankedHits(denseHits, bm25Hits, effectiveCandidateK);
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            log.info("Hybrid similarity candidates by knowledge bases prepared: traceId={}, knowledgeBaseCount={}, topK={}, candidateK={}, denseHits={}, bm25Hits={}, fusedHits={}, durationMs={}",
                    TraceContext.getTraceId(), knowledgeBaseIds.size(), topK, effectiveCandidateK, denseHits.size(), bm25Hits.size(), fusedHits.size(), durationMs);
            return fusedHits;
        }

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        log.warn("Knowledge-base similarity search skipped: traceId={}, knowledgeBaseCount={}, topK={}, durationMs={}, reason=milvus-disabled",
                TraceContext.getTraceId(), knowledgeBaseIds.size(), topK, durationMs);
        return List.of();
    }

    private List<RankedCandidateHit> fuseRankedHits(List<MilvusSearchHit> denseHits, List<MilvusSearchHit> bm25Hits, int limit) {
        Map<String, RankedCandidateHit> fused = new LinkedHashMap<>();
        addHitsByRrf(fused, denseHits, true);
        addHitsByRrf(fused, bm25Hits, false);
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(RankedCandidateHit::fusedScore).reversed())
                .limit(limit)
                .toList();
    }

    private void addHitsByRrf(Map<String, RankedCandidateHit> fused, List<MilvusSearchHit> hits, boolean dense) {
        for (int i = 0; i < hits.size(); i++) {
            MilvusSearchHit hit = hits.get(i);
            if (!StringUtils.hasText(hit.chunkId())) {
                continue;
            }
            int rank = i + 1;
            double rrfScore = 1.0d / (rrfK + i + 1);
            fused.compute(hit.chunkId(), (chunkId, existing) -> {
                if (existing == null) {
                    return new RankedCandidateHit(
                            hit,
                            dense ? rank : null,
                            dense ? null : rank,
                            dense ? hit.score() : null,
                            dense ? null : hit.score(),
                            rrfScore
                    );
                }
                return new RankedCandidateHit(
                        existing.hit(),
                        dense ? rank : existing.denseRank(),
                        dense ? existing.bm25Rank() : rank,
                        dense ? hit.score() : existing.denseScore(),
                        dense ? existing.bm25Score() : hit.score(),
                        existing.fusedScore() + rrfScore
                );
            });
        }
    }

    private RetrievalHit toRetrievalHit(MilvusSearchHit hit) {
        String content = StringUtils.hasText(hit.content()) ? hit.content() : hit.retrievalText();
        Integer chunkIndex = hit.chunkIndex() >= 0 ? hit.chunkIndex() : null;
        String scoreType = StringUtils.hasText(hit.scoreType())
                ? hit.scoreType()
                : (hit.score() == null ? "fallback" : "retrieval");
        return new RetrievalHit(
                RagSourceType.KNOWLEDGE_BASE,
                hit.sourceId(),
                hit.documentId(),
                hit.documentName(),
                chunkIndex,
                hit.sectionPath(),
                content,
                hit.contextText(),
                hit.score(),
                scoreType,
                "fallback".equals(scoreType)
        );
    }

    public record RankedCandidateHit(
            MilvusSearchHit hit,
            Integer denseRank,
            Integer bm25Rank,
            Double denseScore,
            Double bm25Score,
            double fusedScore
    ) {
    }
}
