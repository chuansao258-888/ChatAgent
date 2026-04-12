package com.yulong.chatagent.rag.retrieve;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * LLM-based reranker that asks a chat model to reorder candidate chunk ids.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "rag.retrieval.reranker", name = "provider", havingValue = "llm")
@Slf4j
public class LlmRetrievalReranker implements RetrievalReranker {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final PromptLoader promptLoader;
    private final ChatModelRouter chatModelRouter;
    private final RerankerProperties properties;
    private final ObjectMapper objectMapper;

    public LlmRetrievalReranker(PromptLoader promptLoader,
                                ChatModelRouter chatModelRouter,
                                RerankerProperties properties,
                                ObjectMapper objectMapper) {
        this.promptLoader = promptLoader;
        this.chatModelRouter = chatModelRouter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<MilvusSearchHit> rerank(String queryText, List<MilvusSearchHit> candidates) {
        if (!StringUtils.hasText(queryText) || candidates == null || candidates.size() < 2) {
            return candidates;
        }

        int limit = Math.min(candidates.size(), Math.max(2, properties.getMaxCandidates()));
        List<MilvusSearchHit> rerankCandidates = new ArrayList<>(candidates.subList(0, limit));
        long startTime = System.nanoTime();
        try {
            ChatClient chatClient = chatModelRouter.route(properties.getModelId());
            log.info("Retrieval rerank started: traceId={}, modelId={}, candidateCount={}",
                    TraceContext.getTraceId(), properties.getModelId(), rerankCandidates.size());

            String response = chatClient.prompt()
                    .system(promptLoader.load(PromptConstants.RAG_RERANKER_SYSTEM))
                    .user(buildUserPrompt(queryText, rerankCandidates))
                    .call()
                    .content();

            List<String> rankedIds = parseRankedIds(response);
            if (rankedIds.isEmpty()) {
                return candidates;
            }

            List<MilvusSearchHit> reranked = applyRanking(candidates, rankedIds);
            log.info("Retrieval rerank completed: traceId={}, modelId={}, returnedIds={}, durationMs={}",
                    TraceContext.getTraceId(), properties.getModelId(), rankedIds.size(),
                    (System.nanoTime() - startTime) / 1_000_000);
            return reranked;
        } catch (Exception e) {
            // Retrieval should keep the fused order if reranking is unavailable or malformed.
            log.warn("Retrieval rerank skipped: traceId={}, error={}", TraceContext.getTraceId(), e.getMessage());
            return candidates;
        }
    }

    /**
     * Builds a compact rerank prompt so the model sees only the discriminative parts of each
     * candidate rather than the full chunk body.
     */
    String buildUserPrompt(String queryText, List<MilvusSearchHit> candidates) {
        StringBuilder builder = new StringBuilder();
        builder.append("Query:\n").append(queryText).append("\n\nCandidates:\n");
        for (MilvusSearchHit candidate : candidates) {
            builder.append("- chunkId: ").append(candidate.chunkId()).append("\n");
            builder.append("  documentName: ").append(nullToEmpty(candidate.documentName())).append("\n");
            builder.append("  chunkIndex: ").append(candidate.chunkIndex()).append("\n");
            if (!candidate.documentKeywords().isEmpty()) {
                builder.append("  documentKeywords: ")
                        .append(toSingleLine(String.join(", ", candidate.documentKeywords())))
                        .append("\n");
            }
            if (!candidate.documentQuestions().isEmpty()) {
                builder.append("  documentQuestions: ")
                        .append(toSingleLine(String.join(" | ", candidate.documentQuestions())))
                        .append("\n");
            }
            if (StringUtils.hasText(candidate.contextText())) {
                builder.append("  context: ").append(toSingleLine(truncate(candidate.contextText()))).append("\n");
            }
            builder.append("  content: ").append(toSingleLine(truncate(resolveCandidateText(candidate)))).append("\n\n");
        }
        return builder.toString();
    }

    private String resolveCandidateText(MilvusSearchHit candidate) {
        if (StringUtils.hasText(candidate.content())) {
            return candidate.content();
        }
        return candidate.retrievalText();
    }

    private String truncate(String text) {
        if (!StringUtils.hasText(text) || text.length() <= properties.getMaxChunkChars()) {
            return text;
        }
        return text.substring(0, properties.getMaxChunkChars());
    }

    private String toSingleLine(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Accepts raw JSON arrays as well as fenced-code JSON blocks returned by some chat models.
     */
    private List<String> parseRankedIds(String response) {
        if (!StringUtils.hasText(response)) {
            return List.of();
        }
        String normalized = response.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```[a-zA-Z0-9_-]*\\s*", "");
            normalized = normalized.replaceFirst("\\s*```$", "");
            normalized = normalized.trim();
        }
        try {
            return objectMapper.readValue(normalized, STRING_LIST_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse reranker response as chunkId array", e);
        }
    }

    /**
     * Reorders the original list while preserving any candidates omitted by the reranker.
     */
    private List<MilvusSearchHit> applyRanking(List<MilvusSearchHit> original, List<String> rankedIds) {
        Map<String, MilvusSearchHit> byId = new LinkedHashMap<>();
        for (MilvusSearchHit hit : original) {
            if (StringUtils.hasText(hit.chunkId())) {
                byId.put(hit.chunkId(), hit);
            }
        }

        LinkedHashSet<String> orderedIds = new LinkedHashSet<>(rankedIds);
        List<MilvusSearchHit> reranked = new ArrayList<>(original.size());
        for (String chunkId : orderedIds) {
            MilvusSearchHit hit = byId.get(chunkId);
            if (hit != null) {
                reranked.add(hit.withScoreType("retrieval"));
            }
        }
        for (MilvusSearchHit hit : original) {
            if (StringUtils.hasText(hit.chunkId()) && !orderedIds.contains(hit.chunkId())) {
                reranked.add(hit.withScoreType("retrieval"));
            }
        }
        return reranked;
    }
}
