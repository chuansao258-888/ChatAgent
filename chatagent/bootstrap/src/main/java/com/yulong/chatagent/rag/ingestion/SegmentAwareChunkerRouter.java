package com.yulong.chatagent.rag.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import com.yulong.chatagent.rag.parser.ParseSegment;
import com.yulong.chatagent.rag.parser.SegmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase-2 adapter that lets the pipeline consume ParseSegment while reusing the existing text
 * chunkers underneath.
 */
@Component
@RequiredArgsConstructor
public class SegmentAwareChunkerRouter implements DocumentChunker {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final StructureAwareMarkdownChunker markdownChunker;
    private final PlainTextChunker plainTextChunker;
    private final ObjectMapper objectMapper;

    @Value("${chatagent.rag.chunk.page.target-chars:1200}")
    private int pageTargetChars;

    @Value("${chatagent.rag.chunk.page.max-chars:1800}")
    private int pageMaxChars;

    @Override
    public List<KnowledgeChunkDraft> chunk(List<ParseSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }

        SegmentType dominantType = segments.get(0).type();
        return switch (dominantType) {
            case FULL -> chunkFullSegment(segments.get(0));
            case PAGE -> chunkPageSegments(segments);
            case FIGURE -> chunkStandaloneSegments(segments);
            case TABLE, SECTION -> plainTextChunker.chunk(
                    segments.stream()
                            .map(ParseSegment::text)
                            .filter(StringUtils::hasText)
                            .collect(Collectors.joining("\n\n"))
            );
        };
    }

    private List<KnowledgeChunkDraft> chunkFullSegment(ParseSegment segment) {
        String text = segment.text();
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        if (looksLikeMarkdown(text)) {
            return markdownChunker.chunk(text);
        }
        return plainTextChunker.chunk(text);
    }

    private boolean looksLikeMarkdown(String text) {
        if (!StringUtils.hasText(text) || text.length() < 10) {
            return false;
        }
        return text.contains("\n#")
                || text.contains("\n```")
                || text.startsWith("#")
                || text.contains("\n|")
                || text.contains("|---");
    }

    private List<KnowledgeChunkDraft> chunkPageSegments(List<ParseSegment> pages) {
        List<KnowledgeChunkDraft> drafts = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        List<ParseSegment> bufferedSegments = new ArrayList<>();

        for (ParseSegment page : pages) {
            if (!StringUtils.hasText(page.text())) {
                continue;
            }

            if (isStandaloneVisual(page)) {
                flushPageBuffer(buffer, bufferedSegments, drafts);
                resetPageBuffer(buffer, bufferedSegments);
                drafts.addAll(chunkStandaloneSegment(page));
                continue;
            }

            if (page.charCount() > pageMaxChars) {
                flushPageBuffer(buffer, bufferedSegments, drafts);
                List<KnowledgeChunkDraft> chunked = looksLikeMarkdown(page.text())
                        ? markdownChunker.chunk(page.text())
                        : plainTextChunker.chunk(page.text());
                drafts.addAll(attachSegmentMetadata(chunked, List.of(page), SegmentType.PAGE));
                resetPageBuffer(buffer, bufferedSegments);
                continue;
            }

            int nextLength = buffer.length() + (buffer.length() > 0 ? 2 : 0) + page.charCount();
            if (nextLength > pageTargetChars && buffer.length() > 0) {
                flushPageBuffer(buffer, bufferedSegments, drafts);
                resetPageBuffer(buffer, bufferedSegments);
            }

            if (buffer.length() > 0) {
                buffer.append("\n\n");
            }
            buffer.append(page.text());
            bufferedSegments.add(page);
        }

        flushPageBuffer(buffer, bufferedSegments, drafts);
        return reindexChunkMetadata(drafts);
    }

    private boolean isStandaloneVisual(ParseSegment segment) {
        if (segment == null) {
            return false;
        }
        if (segment.type() == SegmentType.FIGURE) {
            return true;
        }
        Object visualType = segment.metadata().get("visualType");
        return visualType instanceof String value && "TABLE".equalsIgnoreCase(value.trim());
    }

    private List<KnowledgeChunkDraft> chunkStandaloneSegment(ParseSegment segment) {
        List<KnowledgeChunkDraft> chunked = looksLikeMarkdown(segment.text())
                ? markdownChunker.chunk(segment.text())
                : plainTextChunker.chunk(segment.text());
        return attachSegmentMetadata(chunked, List.of(segment), segment.type());
    }

    private List<KnowledgeChunkDraft> chunkStandaloneSegments(List<ParseSegment> segments) {
        List<KnowledgeChunkDraft> drafts = new ArrayList<>();
        for (ParseSegment segment : segments) {
            drafts.addAll(chunkStandaloneSegment(segment));
        }
        return reindexChunkMetadata(drafts);
    }

    private void resetPageBuffer(StringBuilder buffer, List<ParseSegment> bufferedSegments) {
        if (buffer != null) {
            buffer.setLength(0);
        }
        if (bufferedSegments != null) {
            bufferedSegments.clear();
        }
    }

    private void flushPageBuffer(StringBuilder buffer,
                                 List<ParseSegment> sourceSegments,
                                 List<KnowledgeChunkDraft> drafts) {
        String text = buffer == null ? "" : buffer.toString().trim();
        if (!StringUtils.hasText(text)) {
            return;
        }
        if (text.length() > pageMaxChars && looksLikeMarkdown(text)) {
            drafts.addAll(attachSegmentMetadata(markdownChunker.chunk(text), sourceSegments, SegmentType.PAGE));
            return;
        }
        drafts.add(new KnowledgeChunkDraft(text, buildPageMetadata(text, sourceSegments), text));
    }

    private List<KnowledgeChunkDraft> attachSegmentMetadata(List<KnowledgeChunkDraft> drafts,
                                                            List<ParseSegment> sourceSegments,
                                                            SegmentType sourceType) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        List<KnowledgeChunkDraft> result = new ArrayList<>(drafts.size());
        for (KnowledgeChunkDraft draft : drafts) {
            Map<String, Object> metadata = parseMetadata(draft.metadata());
            metadata.put("sourceSegmentType", sourceType.name());
            metadata.putAll(buildPageStats(sourceSegments));
            metadata.put("contentLength", draft.content() == null ? 0 : draft.content().length());
            if (sourceSegments != null && sourceSegments.size() == 1) {
                metadata.putAll(sourceSegments.get(0).metadata());
            }
            result.add(new KnowledgeChunkDraft(draft.content(), writeMetadata(metadata), draft.embeddingText()));
        }
        return result;
    }

    private String buildPageMetadata(String text, List<ParseSegment> sourceSegments) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunkStrategy", "page_aware_segment");
        metadata.put("contentLength", text.length());
        metadata.put("sourceSegmentType", SegmentType.PAGE.name());
        metadata.putAll(buildPageStats(sourceSegments));
        return writeMetadata(metadata);
    }

    private Map<String, Object> buildPageStats(List<ParseSegment> sourceSegments) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        List<Integer> pageNumbers = sourceSegments == null ? List.of() : sourceSegments.stream()
                .map(segment -> segment.metadata().get("pageNumber"))
                .filter(Integer.class::isInstance)
                .map(Integer.class::cast)
                .sorted()
                .toList();
        if (!pageNumbers.isEmpty()) {
            metadata.put("pageStart", pageNumbers.get(0));
            metadata.put("pageEnd", pageNumbers.get(pageNumbers.size() - 1));
            metadata.put("sourcePages", pageNumbers);
        }
        return metadata;
    }

    private List<KnowledgeChunkDraft> reindexChunkMetadata(List<KnowledgeChunkDraft> drafts) {
        List<KnowledgeChunkDraft> reindexed = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            KnowledgeChunkDraft draft = drafts.get(i);
            Map<String, Object> metadata = parseMetadata(draft.metadata());
            metadata.put("chunkIndex", i);
            metadata.put("contentLength", draft.content() == null ? 0 : draft.content().length());
            reindexed.add(new KnowledgeChunkDraft(draft.content(), writeMetadata(metadata), draft.embeddingText()));
        }
        return reindexed;
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(metadataJson, MAP_TYPE));
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize segment chunk metadata", e);
        }
    }
}
