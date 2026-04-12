package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.rag.ingestion.enhance.DocumentEnhancerProperties;
import com.yulong.chatagent.rag.ingestion.model.BaseIngestionContext;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeIngestionContext;
import com.yulong.chatagent.rag.parser.ParseSegment;
import com.yulong.chatagent.rag.parser.QualityLevel;
import com.yulong.chatagent.rag.parser.SegmentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Segment-native knowledge document enhancer. Session-file ingestion must never trigger this path.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "chatagent.rag.ingestion.document-enhancer", name = "enabled", havingValue = "true")
@Slf4j
public class LlmDocumentEnhancer implements DocumentEnhancer {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final PromptLoader promptLoader;
    private final ChatModelRouter chatModelRouter;
    private final DocumentEnhancerProperties properties;
    private final ObjectMapper objectMapper;

    public LlmDocumentEnhancer(PromptLoader promptLoader,
                               ChatModelRouter chatModelRouter,
                               DocumentEnhancerProperties properties,
                               ObjectMapper objectMapper) {
        this.promptLoader = promptLoader;
        this.chatModelRouter = chatModelRouter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public DocumentEnhancementResult enhance(BaseIngestionContext context) {
        if (!(context instanceof KnowledgeIngestionContext knowledgeContext)) {
            return DocumentEnhancementResult.empty();
        }
        List<ParseSegment> segments = safeSegments(context.getSegments());
        if (segments.isEmpty()) {
            return DocumentEnhancementResult.empty();
        }

        try {
            int totalChars = segments.stream().mapToInt(ParseSegment::charCount).sum();
            boolean isSingleFull = segments.size() == 1 && segments.get(0).type() == SegmentType.FULL;
            boolean isShort = totalChars <= properties.getShortDocCharLimit();
            boolean lowQuality = context.getParseResult() != null
                    && context.getParseResult().getQualityLevel() == QualityLevel.LOW;
            String cacheKey = buildCacheKey(segments, isSingleFull && isShort && !lowQuality ? "short_full" : "meta_only");

            if (isSingleFull && isShort && !lowQuality) {
                DocumentEnhancementResult result = enhanceShortFullDocument(segments.get(0), cacheKey);
                log.info("Document enhancement completed: documentId={}, mode=short_full, enhancedSegments={}, keywords={}, questions={}",
                        knowledgeContext.getDocumentId(),
                        result.enhancedSegments() == null ? 0 : result.enhancedSegments().size(),
                        result.keywords().size(),
                        result.questions().size());
                return result;
            }

            DocumentEnhancementResult result = extractMetaOnly(segments, cacheKey);
            log.info("Document enhancement completed: documentId={}, mode=meta_only, windowsKeywords={}, questions={}",
                    knowledgeContext.getDocumentId(),
                    result.keywords().size(),
                    result.questions().size());
            return result;
        } catch (Exception e) {
            log.warn("Document enhancement skipped: documentId={}, error={}", knowledgeContext.getDocumentId(), e.getMessage());
            return DocumentEnhancementResult.empty();
        }
    }

    private DocumentEnhancementResult enhanceShortFullDocument(ParseSegment segment, String cacheKey) {
        String rawText = segment.text();
        String enhancedBody = runContextEnhance(rawText);
        if (!passesLengthGuard(rawText, enhancedBody)) {
            enhancedBody = rawText;
        }
        DocMetaExtractResult meta = runDocMetaExtract(enhancedBody);
        return new DocumentEnhancementResult(
                List.of(new ParseSegment(enhancedBody, segment.index(), SegmentType.FULL, segment.metadata())),
                normalizeList(meta.keywords(), properties.getMaxKeywords(), properties.getMaxKeywordChars()),
                normalizeList(meta.questions(), properties.getMaxQuestions(), properties.getMaxQuestionChars()),
                normalizeMetadata(meta.metadata()),
                cacheKey
        );
    }

    private DocumentEnhancementResult extractMetaOnly(List<ParseSegment> segments, String cacheKey) {
        List<String> windows = splitToWindows(segments, properties.getMapWindowMaxChars());
        if (windows.isEmpty()) {
            return new DocumentEnhancementResult(null, List.of(), List.of(), Map.of(), cacheKey);
        }

        List<DocMetaExtractResult> mapResults = new ArrayList<>(windows.size());
        for (int i = 0; i < windows.size(); i++) {
            String window = windows.get(i);
            try {
                mapResults.add(runDocMetaExtract(window));
            } catch (Exception e) {
                log.warn("Document meta extraction window skipped: windowIndex={}, windowChars={}, error={}",
                        i,
                        window == null ? 0 : window.length(),
                        e.getMessage());
            }
        }
        if (mapResults.isEmpty()) {
            log.warn("Document meta extraction produced no successful windows; falling back to empty metadata");
            return new DocumentEnhancementResult(null, List.of(), List.of(), Map.of(), cacheKey);
        }
        DocMetaExtractResult reduced = reduceMetaResults(mapResults);
        return new DocumentEnhancementResult(
                null,
                normalizeList(reduced.keywords(), properties.getMaxKeywords(), properties.getMaxKeywordChars()),
                normalizeList(reduced.questions(), properties.getMaxQuestions(), properties.getMaxQuestionChars()),
                normalizeMetadata(reduced.metadata()),
                cacheKey
        );
    }

    private String runContextEnhance(String rawText) {
        ChatClient chatClient = chatModelRouter.route(properties.getModelId());
        String response = chatClient.prompt()
                .system(promptLoader.load(PromptConstants.RAG_DOC_CLEANUP))
                .user(rawText)
                .call()
                .content();
        return StringUtils.hasText(response) ? response.trim() : rawText;
    }

    DocMetaExtractResult runDocMetaExtract(String text) {
        ChatClient chatClient = chatModelRouter.route(properties.getModelId());
        String response = chatClient.prompt()
                .system(promptLoader.load(PromptConstants.RAG_DOC_METADATA))
                .user("""
                        Document:
                        %s
                        """.formatted(text))
                .call()
                .content();
        return parseMetaExtractResponse(response);
    }

    private DocMetaExtractResult parseMetaExtractResponse(String response) {
        if (!StringUtils.hasText(response)) {
            return new DocMetaExtractResult(List.of(), List.of(), Map.of());
        }
        String normalized = response.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```[a-zA-Z0-9_-]*\\s*", "");
            normalized = normalized.replaceFirst("\\s*```$", "");
            normalized = normalized.trim();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(normalized, MAP_TYPE);
            return new DocMetaExtractResult(
                    toStringList(parsed.get("keywords")),
                    toStringList(parsed.get("questions")),
                    toMetadataMap(parsed.get("metadata"))
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse document enhancement metadata response", e);
        }
    }

    private DocMetaExtractResult reduceMetaResults(List<DocMetaExtractResult> results) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        LinkedHashSet<String> questions = new LinkedHashSet<>();
        boolean containsPii = false;
        String docType = "other";

        for (DocMetaExtractResult result : results) {
            keywords.addAll(normalizeList(result.keywords(), properties.getMaxKeywords(), properties.getMaxKeywordChars()));
            questions.addAll(normalizeList(result.questions(), properties.getMaxQuestions(), properties.getMaxQuestionChars()));
            Map<String, Object> metadata = normalizeMetadata(result.metadata());
            containsPii = containsPii || Boolean.TRUE.equals(metadata.get("contains_pii"));
            if ("other".equals(docType) && metadata.get("doc_type") instanceof String candidate && !"other".equals(candidate)) {
                docType = candidate;
            }
        }

        return new DocMetaExtractResult(
                new ArrayList<>(keywords),
                new ArrayList<>(questions),
                Map.of("doc_type", docType, "contains_pii", containsPii)
        );
    }

    private List<String> splitToWindows(List<ParseSegment> segments, int maxCharsPerWindow) {
        List<String> windows = new ArrayList<>();
        if (segments == null || segments.isEmpty() || maxCharsPerWindow <= 0) {
            return windows;
        }

        StringBuilder buffer = new StringBuilder(Math.min(maxCharsPerWindow + 256, 65_536));
        for (ParseSegment seg : segments) {
            if (!StringUtils.hasText(seg.text())) {
                continue;
            }
            if (seg.charCount() > maxCharsPerWindow) {
                if (buffer.length() > 0) {
                    windows.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
                windows.addAll(splitTextByParagraphs(seg.text(), maxCharsPerWindow));
                continue;
            }

            int nextLength = buffer.length() + (buffer.length() > 0 ? 2 : 0) + seg.charCount();
            if (nextLength > maxCharsPerWindow && buffer.length() > 0) {
                windows.add(buffer.toString().trim());
                buffer.setLength(0);
            }
            if (buffer.length() > 0) {
                buffer.append("\n\n");
            }
            buffer.append(seg.text());
        }

        if (buffer.length() > 0) {
            windows.add(buffer.toString().trim());
        }
        return windows;
    }

    private List<String> splitTextByParagraphs(String text, int maxChars) {
        List<String> windows = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return windows;
        }
        String[] paragraphs = text.split("\\n\\n");
        StringBuilder buffer = new StringBuilder(Math.min(maxChars + 128, 65_536));
        for (String paragraph : paragraphs) {
            String normalized = paragraph == null ? "" : paragraph.trim();
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            if (normalized.length() > maxChars) {
                if (buffer.length() > 0) {
                    windows.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
                int start = 0;
                while (start < normalized.length()) {
                    int end = Math.min(start + maxChars, normalized.length());
                    windows.add(normalized.substring(start, end).trim());
                    start = end;
                }
                continue;
            }

            int nextLength = buffer.length() + (buffer.length() > 0 ? 2 : 0) + normalized.length();
            if (nextLength > maxChars && buffer.length() > 0) {
                windows.add(buffer.toString().trim());
                buffer.setLength(0);
            }
            if (buffer.length() > 0) {
                buffer.append("\n\n");
            }
            buffer.append(normalized);
        }
        if (buffer.length() > 0) {
            windows.add(buffer.toString().trim());
        }
        return windows;
    }

    private boolean passesLengthGuard(String rawText, String enhancedBody) {
        if (!StringUtils.hasText(rawText) || !StringUtils.hasText(enhancedBody)) {
            return false;
        }
        double ratio = (double) enhancedBody.length() / (double) rawText.length();
        return ratio >= properties.getMinEnhancedLengthRatio() && ratio <= properties.getMaxEnhancedLengthRatio();
    }

    private List<String> normalizeList(List<String> values, int maxItems, int maxChars) {
        if (values == null || values.isEmpty() || maxItems <= 0) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.length() > maxChars) {
                trimmed = trimmed.substring(0, maxChars).trim();
            }
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }
            normalized.add(trimmed);
            if (normalized.size() >= maxItems) {
                break;
            }
        }
        return List.copyOf(normalized);
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        String docType = "other";
        boolean containsPii = false;
        if (metadata != null) {
            Object rawDocType = metadata.get("doc_type");
            if (rawDocType instanceof String value) {
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if (List.of("policy", "manual", "code", "invoice", "other").contains(normalized)) {
                    docType = normalized;
                }
            }
            Object rawContainsPii = metadata.get("contains_pii");
            if (rawContainsPii instanceof Boolean value) {
                containsPii = value;
            }
        }
        return Map.of(
                "doc_type", docType,
                "contains_pii", containsPii
        );
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .toList();
    }

    private Map<String, Object> toMetadataMap(Object value) {
        if (!(value instanceof Map<?, ?> values)) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        values.forEach((key, candidate) -> {
            if (key != null) {
                metadata.put(key.toString(), candidate);
            }
        });
        return metadata;
    }

    private String buildCacheKey(List<ParseSegment> segments, String mode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, properties.getPromptVersion());
            updateDigest(digest, properties.getModelId());
            updateDigest(digest, mode);
            for (ParseSegment segment : safeSegments(segments)) {
                updateDigest(digest, segment.type().name());
                updateDigest(digest, Integer.toString(segment.index()));
                updateDigest(digest, segment.text());
            }
            byte[] hash = digest.digest();
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    private List<ParseSegment> safeSegments(List<ParseSegment> segments) {
        return segments == null ? List.of() : segments;
    }

    record DocMetaExtractResult(
            List<String> keywords,
            List<String> questions,
            Map<String, Object> metadata
    ) {
    }
}
