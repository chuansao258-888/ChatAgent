package com.yulong.chatagent.rag.retrieve;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for a locally deployed BGE reranker service.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "rag.retrieval.reranker", name = "provider", havingValue = "bge-http")
@Slf4j
public class BgeHttpRetrievalReranker implements RetrievalReranker {

    private final WebClient webClient;
    private final RerankerProperties properties;

    public BgeHttpRetrievalReranker(WebClient.Builder builder, RerankerProperties properties) {
        this.webClient = builder.baseUrl(properties.getBaseUrl()).build();
        this.properties = properties;
    }

    @Override
    public List<MilvusSearchHit> rerank(String queryText, List<MilvusSearchHit> candidates) {
        if (!StringUtils.hasText(queryText) || candidates == null || candidates.size() < 2) {
            return candidates;
        }

        int limit = Math.min(candidates.size(), Math.max(2, properties.getMaxCandidates()));
        List<MilvusSearchHit> rerankCandidates = new ArrayList<>(candidates.subList(0, limit));
        List<String> documents = rerankCandidates.stream()
                .map(this::buildDocument)
                .toList();

        long startTime = System.nanoTime();
        try {
            BgeRerankRequest request = new BgeRerankRequest(
                    properties.getModelId(),
                    queryText,
                    documents,
                    limit,
                    false
            );
            log.info("BGE rerank started: traceId={}, modelId={}, candidateCount={}, baseUrl={}, path={}",
                    TraceContext.getTraceId(), properties.getModelId(), rerankCandidates.size(),
                    properties.getBaseUrl(), properties.getPath());

            BgeRerankResponse response = webClient.post()
                    .uri(properties.getPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> applyHeaders(headers))
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(BgeRerankResponse.class)
                    .block();

            if (response == null) {
                return candidates;
            }

            List<String> rankedIds = extractRankedIds(response, rerankCandidates);
            if (rankedIds.isEmpty()) {
                return candidates;
            }

            List<MilvusSearchHit> reranked = applyRanking(candidates, rankedIds);
            log.info("BGE rerank completed: traceId={}, modelId={}, rankedIds={}, durationMs={}",
                    TraceContext.getTraceId(), properties.getModelId(), rankedIds.size(),
                    (System.nanoTime() - startTime) / 1_000_000);
            return reranked;
        } catch (Exception e) {
            // Retrieval should keep the fused order if the local reranker is unavailable.
            log.warn("BGE rerank skipped: traceId={}, error={}", TraceContext.getTraceId(), e.getMessage());
            return candidates;
        }
    }

    private void applyHeaders(HttpHeaders headers) {
        if (StringUtils.hasText(properties.getApiKey())) {
            headers.setBearerAuth(properties.getApiKey());
        }
    }

    private String buildDocument(MilvusSearchHit hit) {
        String content = StringUtils.hasText(hit.content()) ? hit.content() : hit.retrievalText();
        if (!StringUtils.hasText(hit.contextText())) {
            return truncate(content);
        }
        // The local reranker scores the same context + content payload shape shown to the LLM.
        return truncate("Context: " + hit.contextText() + "\n\nContent: " + content);
    }

    private String truncate(String text) {
        if (!StringUtils.hasText(text) || text.length() <= properties.getMaxChunkChars()) {
            return text;
        }
        return text.substring(0, properties.getMaxChunkChars());
    }

    private List<String> extractRankedIds(BgeRerankResponse response, List<MilvusSearchHit> candidates) {
        List<BgeRerankResult> results = response.results();
        if (results == null || results.isEmpty()) {
            results = response.data();
        }
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results.stream()
                .filter(result -> result.index() != null && result.index() >= 0 && result.index() < candidates.size())
                .sorted(Comparator.comparingDouble(this::scoreOf).reversed())
                .map(result -> candidates.get(result.index()).chunkId())
                .filter(StringUtils::hasText)
                .toList();
    }

    private double scoreOf(BgeRerankResult result) {
        if (result.relevanceScore() != null) {
            return result.relevanceScore();
        }
        if (result.score() != null) {
            return result.score();
        }
        return 0.0d;
    }

    private List<MilvusSearchHit> applyRanking(List<MilvusSearchHit> original, List<String> rankedIds) {
        Map<String, MilvusSearchHit> byId = new LinkedHashMap<>();
        for (MilvusSearchHit hit : original) {
            if (StringUtils.hasText(hit.chunkId())) {
                byId.put(hit.chunkId(), hit);
            }
        }

        List<MilvusSearchHit> reranked = new ArrayList<>(original.size());
        for (String chunkId : rankedIds) {
            MilvusSearchHit hit = byId.remove(chunkId);
            if (hit != null) {
                reranked.add(hit);
            }
        }
        reranked.addAll(byId.values());
        return reranked;
    }

    private record BgeRerankRequest(
            String model,
            String query,
            List<String> documents,
            int top_n,
            boolean return_documents
    ) {
    }

    private record BgeRerankResponse(
            List<BgeRerankResult> results,
            List<BgeRerankResult> data
    ) {
    }

    private record BgeRerankResult(
            Integer index,
            @JsonProperty("relevance_score")
            Double relevanceScore,
            Double score
    ) {
    }
}
