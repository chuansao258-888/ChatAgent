package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.rag.ingestion.enrich.ContextualChunkEnricherProperties;
import com.yulong.chatagent.rag.ingestion.model.BaseIngestionContext;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeIngestionContext;
import com.yulong.chatagent.rag.ingestion.model.SessionIngestionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chunk-level contextualizer inspired by Anthropic contextual retrieval.
 *
 * <p>Each qualifying chunk receives a short chunk-specific context string, and the resulting
 * {@code retrievalText = contextText + chunkContent} is stored back into chunk metadata for
 * dense, BM25, and rerank stages.</p>
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "chatagent.rag.ingestion.contextual-enricher", name = "enabled", havingValue = "true")
@Slf4j
public class LlmContextualChunkEnricher implements ChunkEnricher {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final PromptLoader promptLoader;
    private final ChatModelRouter chatModelRouter;
    private final ContextualChunkEnricherProperties properties;
    private final ObjectMapper objectMapper;

    public LlmContextualChunkEnricher(PromptLoader promptLoader,
                                      ChatModelRouter chatModelRouter,
                                      ContextualChunkEnricherProperties properties,
                                      ObjectMapper objectMapper) {
        this.promptLoader = promptLoader;
        this.chatModelRouter = chatModelRouter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<KnowledgeChunkDraft> enrich(BaseIngestionContext context, List<KnowledgeChunkDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return drafts;
        }

        String wholeDocument = resolveWholeDocument(context);
        if (!StringUtils.hasText(wholeDocument)) {
            return drafts;
        }

        String promptDocument = truncate(wholeDocument, properties.getMaxDocumentChars());
        ChatClient chatClient = chatModelRouter.route(properties.getModelId());
        List<KnowledgeChunkDraft> enriched = new ArrayList<>(drafts.size());
        int contextualizedCount = 0;

        for (int i = 0; i < drafts.size(); i++) {
            KnowledgeChunkDraft draft = drafts.get(i);
            if (!shouldContextualize(draft, contextualizedCount)) {
                enriched.add(draft);
                continue;
            }

            long chunkStart = System.nanoTime();
            try {
                log.info("Contextual chunk enrichment started: sourceId={}, chunkIndex={}, contentLength={}, modelId={}",
                        resolveContextOwnerId(context),
                        i,
                        draft.content() == null ? 0 : draft.content().length(),
                        properties.getModelId());

                String response = chatClient.prompt()
                        .system(promptLoader.load(PromptConstants.RAG_CHUNK_CTX_SYSTEM))
                        .user(buildUserPrompt(promptDocument, draft.content()))
                        .call()
                        .content();

                String contextText = normalizeContext(response, draft.content().length());
                if (!StringUtils.hasText(contextText)) {
                    enriched.add(draft);
                    continue;
                }

                // Retrieval text becomes the shared source for dense embeddings, BM25 text, and
                // downstream reranking.
                String retrievalText = contextText + System.lineSeparator() + System.lineSeparator() + draft.content();
                Map<String, Object> metadata = parseMetadata(draft.metadata());
                metadata.put("contextText", contextText);
                metadata.put("retrievalText", retrievalText);
                metadata.put("chunkEnrichment", "contextual_retrieval");
                metadata.put("contextModelId", properties.getModelId());

                enriched.add(new KnowledgeChunkDraft(
                        draft.content(),
                        writeMetadata(metadata),
                        retrievalText
                ));
                contextualizedCount++;
                log.info("Contextual chunk enrichment completed: sourceId={}, chunkIndex={}, contextLength={}, durationMs={}",
                        resolveContextOwnerId(context),
                        i,
                        contextText.length(),
                        (System.nanoTime() - chunkStart) / 1_000_000);
            } catch (Exception e) {
                log.warn("Contextual chunk enrichment skipped: sourceId={}, chunkIndex={}, error={}",
                        resolveContextOwnerId(context),
                        i,
                        e.getMessage());
                enriched.add(draft);
            }
        }

        return enriched;
    }

    /**
     * Limits contextualization to sufficiently large chunks and to a bounded number of chunks per
     * file so that enrichment cost stays predictable.
     */
    private boolean shouldContextualize(KnowledgeChunkDraft draft, int contextualizedCount) {
        return draft != null
                && StringUtils.hasText(draft.content())
                && draft.content().length() >= properties.getMinChunkChars()
                && contextualizedCount < properties.getMaxChunksPerFile();
    }

    private String resolveWholeDocument(BaseIngestionContext context) {
        return context.resolveDocumentPrefix(properties.getMaxDocumentChars());
    }

    private String resolveContextOwnerId(BaseIngestionContext context) {
        if (context instanceof SessionIngestionContext sessionContext) {
            return sessionContext.getSessionFile() == null ? null : sessionContext.getSessionFile().getId();
        }
        if (context instanceof KnowledgeIngestionContext knowledgeContext) {
            return knowledgeContext.getDocumentId();
        }
        return null;
    }

    private String buildUserPrompt(String wholeDocument, String chunkContent) {
        return promptLoader.render(PromptConstants.RAG_CHUNK_CTX_USER, Map.of(
                "document", wholeDocument,
                "chunk", chunkContent,
                "maxContextChars", String.valueOf(properties.getMaxContextChars())
        ));
    }

    private String normalizeContext(String response, int chunkLength) {
        if (!StringUtils.hasText(response)) {
            return response;
        }
        String normalized = response
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```[a-zA-Z0-9_-]*\\s*", "");
            normalized = normalized.replaceFirst("\\s*```$", "");
            normalized = normalized.trim();
        }
        int allowedLength = Math.min(properties.getMaxContextChars(), Math.max(80, chunkLength / 2));
        if (normalized.length() > allowedLength) {
            normalized = normalized.substring(0, allowedLength).trim();
        }
        return normalized;
    }

    private String truncate(String text, int maxChars) {
        if (!StringUtils.hasText(text) || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(metadataJson, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse chunk metadata for contextual enrichment, using empty metadata: error={}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize contextual chunk metadata", e);
        }
    }
}
