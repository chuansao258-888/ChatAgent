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
import java.util.Set;
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
    private final TableAwareChunker tableAwareChunker;
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
            case TABLE -> isSpreadsheetSegment(segments.get(0))
                    ? chunkSpreadsheetTables(segments)
                    : plainTextChunker.chunk(
                    segments.stream()
                            .map(ParseSegment::text)
                            .filter(StringUtils::hasText)
                            .collect(Collectors.joining("\n\n"))
            );
            case SECTION -> plainTextChunker.chunk(
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
        List<KnowledgeChunkDraft> raw;
        if (isMarkdownSegment(segment) || looksLikeMarkdown(text)) {
            raw = markdownChunker.chunk(text);
        } else {
            raw = plainTextChunker.chunk(text);
        }
        return attachSegmentMetadata(raw, List.of(segment), SegmentType.FULL);
    }

    private boolean isMarkdownSegment(ParseSegment segment) {
        if (segment == null || segment.metadata() == null) {
            return false;
        }
        Object contentFormat = segment.metadata().get("contentFormat");
        if (contentFormat instanceof String value && "MARKDOWN".equalsIgnoreCase(value.trim())) {
            return true;
        }
        Object parserType = segment.metadata().get("parserType");
        return parserType instanceof String value && "markdown".equalsIgnoreCase(value.trim());
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
        if (shouldChunkPagesAsLogicalMarkdown(pages)) {
            return chunkLogicalMarkdownPages(pages);
        }

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

    private boolean shouldChunkPagesAsLogicalMarkdown(List<ParseSegment> pages) {
        if (pages == null || pages.isEmpty()) {
            return false;
        }
        List<ParseSegment> textPages = pages.stream()
                .filter(page -> page != null && StringUtils.hasText(page.text()))
                .toList();
        if (textPages.isEmpty()) {
            return false;
        }
        if (textPages.stream().allMatch(this::isMinerUMarkdownPage)) {
            return true;
        }
        long explicitMarkdownPages = textPages.stream()
                .filter(page -> !isStandaloneVisual(page))
                .filter(this::isPageMarkdownSegment)
                .count();
        if (explicitMarkdownPages > 0) {
            return true;
        }
        long markdownLikePages = textPages.stream()
                .filter(page -> !isStandaloneVisual(page))
                .filter(page -> looksLikeMarkdown(page.text()))
                .count();
        if (textPages.size() == 1) {
            return markdownLikePages == 1;
        }
        return markdownLikePages >= 2 && markdownLikePages * 2 >= textPages.size();
    }

    private boolean isPageMarkdownSegment(ParseSegment page) {
        if (isMarkdownSegment(page) || isMinerUMarkdownPage(page)) {
            return true;
        }
        if (page == null || page.metadata() == null) {
            return false;
        }
        Object restored = page.metadata().get("fontAwareStructureRestored");
        if (restored instanceof Boolean value && value) {
            return true;
        }
        if (restored instanceof String value && "true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        Object headingCount = page.metadata().get("restoredHeadingCount");
        if (headingCount instanceof Number number && number.intValue() > 0) {
            return true;
        }
        if (headingCount != null && StringUtils.hasText(headingCount.toString())) {
            try {
                return Integer.parseInt(headingCount.toString()) > 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean isMinerUMarkdownPage(ParseSegment page) {
        if (page == null || page.metadata() == null) {
            return false;
        }
        Object engineId = page.metadata().get("engineId");
        return engineId instanceof String value && "mineru".equalsIgnoreCase(value.trim());
    }

    private List<KnowledgeChunkDraft> chunkLogicalMarkdownPages(List<ParseSegment> pages) {
        StringBuilder combined = new StringBuilder();
        List<PageTextRange> pageRanges = new ArrayList<>();

        for (ParseSegment page : pages) {
            if (page == null || !StringUtils.hasText(page.text())) {
                continue;
            }
            if (combined.length() > 0) {
                combined.append("\n\n");
            }
            int start = combined.length();
            combined.append(page.text().trim());
            int end = combined.length();
            pageRanges.add(new PageTextRange(start, end, page));
        }

        if (combined.isEmpty()) {
            return List.of();
        }

        List<KnowledgeChunkDraft> markdownDrafts = markdownChunker.chunk(combined.toString());
        List<KnowledgeChunkDraft> drafts = new ArrayList<>(markdownDrafts.size());
        for (KnowledgeChunkDraft draft : markdownDrafts) {
            Map<String, Object> metadata = parseMetadata(draft.metadata());
            List<ParseSegment> sourceSegments = overlappingPageSegments(metadata, pageRanges);
            if (sourceSegments.isEmpty()) {
                sourceSegments = pageRanges.stream().map(PageTextRange::segment).toList();
            }
            metadata.put("sourceSegmentType", SegmentType.PAGE.name());
            metadata.put("logicalPageMarkdown", true);
            metadata.putAll(buildPageStats(sourceSegments));
            metadata.put("contentLength", draft.content() == null ? 0 : draft.content().length());
            if (sourceSegments.size() == 1) {
                metadata.putAll(sourceSegments.get(0).metadata());
            } else {
                metadata.putAll(sharedSegmentMetadata(sourceSegments));
            }
            drafts.add(new KnowledgeChunkDraft(draft.content(), writeMetadata(metadata)));
        }
        return reindexChunkMetadata(drafts);
    }

    private List<ParseSegment> overlappingPageSegments(Map<String, Object> metadata, List<PageTextRange> pageRanges) {
        Integer sourceStart = metadataInt(metadata, "sourceStart");
        Integer sourceEnd = metadataInt(metadata, "sourceEnd");
        if (sourceStart == null || sourceEnd == null || sourceEnd <= sourceStart) {
            return List.of();
        }
        return pageRanges.stream()
                .filter(range -> range.end() > sourceStart && range.start() < sourceEnd)
                .map(PageTextRange::segment)
                .toList();
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
        drafts.add(new KnowledgeChunkDraft(text, buildPageMetadata(text, sourceSegments)));
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
            result.add(new KnowledgeChunkDraft(draft.content(), writeMetadata(metadata)));
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
            reindexed.add(new KnowledgeChunkDraft(draft.content(), writeMetadata(metadata)));
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

    private Integer metadataInt(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null && StringUtils.hasText(value.toString())) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize segment chunk metadata", e);
        }
    }

    private boolean isSpreadsheetSegment(ParseSegment segment) {
        if (segment == null || segment.metadata() == null) {
            return false;
        }
        return "spreadsheet".equals(segment.metadata().get("docType"));
    }

    private List<KnowledgeChunkDraft> chunkSpreadsheetTables(List<ParseSegment> segments) {
        List<KnowledgeChunkDraft> drafts = new ArrayList<>();
        for (ParseSegment segment : segments) {
            if (!StringUtils.hasText(segment.text())) {
                continue;
            }
            drafts.addAll(tableAwareChunker.chunk(segment.text(), segment.metadata()));
        }
        return reindexChunkMetadata(drafts);
    }

    private Map<String, Object> sharedSegmentMetadata(List<ParseSegment> sourceSegments) {
        if (sourceSegments == null || sourceSegments.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>(sourceSegments.get(0).metadata());
        Set<String> keysToKeep = new java.util.HashSet<>(result.keySet());
        for (int i = 1; i < sourceSegments.size(); i++) {
            Map<String, Object> other = sourceSegments.get(i).metadata();
            keysToKeep.removeIf(key -> !other.containsKey(key) || !java.util.Objects.equals(result.get(key), other.get(key)));
        }
        result.keySet().retainAll(keysToKeep);
        return result;
    }

    private record PageTextRange(int start, int end, ParseSegment segment) {
    }
}
