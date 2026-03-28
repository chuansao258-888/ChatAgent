package com.yulong.chatagent.rag.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Markdown chunker that respects headings, code fences, and atomic blocks before packing blocks
 * into retrieval-sized chunks.
 */
@Component
@RequiredArgsConstructor
public class StructureAwareMarkdownChunker {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");
    private static final Pattern CODE_FENCE = Pattern.compile("^```.*$");
    private static final Pattern ATOMIC_IMAGE = Pattern.compile("^!\\[[^]]*]\\([^)]+\\)(?:\\s*\"[^\"]*\")?\\s*$");
    private static final Pattern ATOMIC_LINK = Pattern.compile("^\\[[^]]+]\\([^)]+\\)\\s*$");

    private final ObjectMapper objectMapper;

    @Value("${chatagent.rag.chunk.markdown.target-chars:1400}")
    private int targetChars;

    @Value("${chatagent.rag.chunk.markdown.max-chars:1800}")
    private int maxChars;

    @Value("${chatagent.rag.chunk.markdown.min-chars:600}")
    private int minChars;

    @Value("${chatagent.rag.chunk.markdown.overlap-chars:0}")
    private int overlapChars;

    /**
     * Chunks markdown in two passes:
     * 1. segment markdown into structure-aware blocks
     * 2. pack blocks into size-bounded chunks
     */
    public List<KnowledgeChunkDraft> chunk(String markdownText) {
        if (!StringUtils.hasText(markdownText)) {
            return List.of();
        }

        String normalizedMarkdown = normalizeLineEndings(markdownText);
        List<Block> blocks = segmentToBlocks(normalizedMarkdown);
        if (blocks.isEmpty()) {
            return List.of(buildDraft(normalizedMarkdown.trim(), List.of("paragraph"), 0, null));
        }

        List<ChunkRange> ranges = packBlocksToChunks(blocks);
        List<KnowledgeChunkDraft> drafts = new ArrayList<>();
        String overlapPrefix = "";

        for (int index = 0; index < ranges.size(); index++) {
            ChunkRange range = ranges.get(index);
            String body = normalizedMarkdown.substring(range.start(), range.end()).trim();
            if (!StringUtils.hasText(body)) {
                continue;
            }

            String content = overlapPrefix.isEmpty() ? body : overlapPrefix + body;
            List<String> blockKinds = collectBlockKinds(blocks, range.start(), range.end());
            drafts.add(buildDraft(content, blockKinds, index, range.sectionPath()));

            overlapPrefix = overlapChars > 0 ? tailByChars(body, overlapChars) : "";
        }

        return compactSmallDrafts(drafts);
    }

    private String normalizeLineEndings(String markdownText) {
        return markdownText.replace("\r\n", "\n").replace('\r', '\n');
    }

    private KnowledgeChunkDraft buildDraft(String content, List<String> blockKinds, int chunkIndex, String sectionPath) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunkStrategy", "structure_aware_markdown");
        metadata.put("blockKinds", blockKinds);
        metadata.put("contentLength", content.length());
        metadata.put("chunkIndex", chunkIndex);
        if (StringUtils.hasText(sectionPath)) {
            metadata.put("sectionPath", sectionPath);
        }
        return new KnowledgeChunkDraft(content, writeMetadata(metadata), content);
    }

    /**
     * Merges tiny tail chunks back into neighbors so that headings, badges, or short image/link
     * sections do not become low-value standalone chunks.
     */
    private List<KnowledgeChunkDraft> compactSmallDrafts(List<KnowledgeChunkDraft> drafts) {
        if (drafts.size() < 2) {
            return reindexDrafts(drafts);
        }

        int smallChunkThreshold = Math.max(80, minChars / 4);
        List<KnowledgeChunkDraft> mergedDrafts = new ArrayList<>();

        for (int i = 0; i < drafts.size(); i++) {
            KnowledgeChunkDraft current = drafts.get(i);
            while (contentLength(current) < smallChunkThreshold
                    && i + 1 < drafts.size()
                    && contentLength(current) + 2 + contentLength(drafts.get(i + 1)) <= maxChars) {
                current = mergeDrafts(current, drafts.get(i + 1));
                i++;
            }

            if (contentLength(current) < smallChunkThreshold
                    && !mergedDrafts.isEmpty()
                    && contentLength(mergedDrafts.get(mergedDrafts.size() - 1)) + 2 + contentLength(current) <= maxChars) {
                KnowledgeChunkDraft previous = mergedDrafts.remove(mergedDrafts.size() - 1);
                mergedDrafts.add(mergeDrafts(previous, current));
                continue;
            }

            mergedDrafts.add(current);
        }

        return reindexDrafts(mergedDrafts);
    }

    private int contentLength(KnowledgeChunkDraft draft) {
        return draft == null || draft.content() == null ? 0 : draft.content().length();
    }

    private KnowledgeChunkDraft mergeDrafts(KnowledgeChunkDraft left, KnowledgeChunkDraft right) {
        String mergedContent = left.content().trim() + "\n\n" + right.content().trim();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunkStrategy", "structure_aware_markdown");
        metadata.put("blockKinds", mergeBlockKinds(left.metadata(), right.metadata()));
        metadata.put("contentLength", mergedContent.length());
        metadata.put("mergedSmallChunk", true);
        String sectionPath = mergeSectionPath(left.metadata(), right.metadata());
        if (StringUtils.hasText(sectionPath)) {
            metadata.put("sectionPath", sectionPath);
        }
        return new KnowledgeChunkDraft(mergedContent, writeMetadata(metadata), mergedContent);
    }

    private List<String> mergeBlockKinds(String leftMetadata, String rightMetadata) {
        List<String> blockKinds = new ArrayList<>();
        addBlockKinds(blockKinds, leftMetadata);
        addBlockKinds(blockKinds, rightMetadata);
        return blockKinds;
    }

    private void addBlockKinds(List<String> blockKinds, String metadataJson) {
        try {
            Map<String, Object> metadata = parseMetadata(metadataJson);
            Object value = metadata.get("blockKinds");
            if (value instanceof List<?> values) {
                for (Object item : values) {
                    String kind = String.valueOf(item);
                    if (!blockKinds.contains(kind)) {
                        blockKinds.add(kind);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> parseMetadata(String metadataJson) throws JsonProcessingException {
        if (!StringUtils.hasText(metadataJson)) {
            return Map.of();
        }
        return objectMapper.readValue(metadataJson, MAP_TYPE);
    }

    private List<KnowledgeChunkDraft> reindexDrafts(List<KnowledgeChunkDraft> drafts) {
        List<KnowledgeChunkDraft> reindexedDrafts = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            KnowledgeChunkDraft draft = drafts.get(i);
            Map<String, Object> metadata;
            try {
                metadata = new LinkedHashMap<>(parseMetadata(draft.metadata()));
            } catch (Exception e) {
                metadata = new LinkedHashMap<>();
            }
            metadata.put("chunkStrategy", "structure_aware_markdown");
            metadata.put("contentLength", contentLength(draft));
            metadata.put("chunkIndex", i);
            reindexedDrafts.add(new KnowledgeChunkDraft(
                    draft.content(),
                    writeMetadata(metadata),
                    draft.embeddingText()
            ));
        }
        return reindexedDrafts;
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize markdown chunk metadata", e);
        }
    }

    private List<String> collectBlockKinds(List<Block> blocks, int start, int end) {
        List<String> kinds = new ArrayList<>();
        for (Block block : blocks) {
            if (block.end() <= start || block.start() >= end) {
                continue;
            }
            String kind = block.kind().name().toLowerCase();
            if (!kinds.contains(kind)) {
                kinds.add(kind);
            }
        }
        return kinds;
    }

    private List<ChunkRange> packBlocksToChunks(List<Block> blocks) {
        List<Section> sections = splitIntoSections(blocks);
        List<ChunkRange> ranges = new ArrayList<>();
        for (Section section : sections) {
            ranges.addAll(packSectionBlocks(section));
        }
        return ranges;
    }

    private List<Section> splitIntoSections(List<Block> blocks) {
        List<Section> sections = new ArrayList<>();
        List<Block> current = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        String currentSectionPath = null;

        for (Block block : blocks) {
            // A heading starts a new semantic section, so downstream chunks do not cross section
            // boundaries by default.
            if (block.kind() == BlockKind.HEADING && !current.isEmpty()) {
                sections.add(new Section(current, currentSectionPath));
                current = new ArrayList<>();
            }
            if (block.kind() == BlockKind.HEADING) {
                currentSectionPath = updateHeadingPath(headingPath, block);
            }
            current.add(block);
        }

        if (!current.isEmpty()) {
            sections.add(new Section(current, currentSectionPath));
        }
        return sections;
    }

    private List<ChunkRange> packSectionBlocks(Section section) {
        List<Block> sectionBlocks = section.blocks();
        List<ChunkRange> ranges = new ArrayList<>();
        int index = 0;

        while (index < sectionBlocks.size()) {
            int chunkStart = sectionBlocks.get(index).start();
            int chunkEnd = sectionBlocks.get(index).end();
            int currentSize = chunkEnd - chunkStart;

            int next = index + 1;
            while (next < sectionBlocks.size()) {
                Block block = sectionBlocks.get(next);
                int expandedSize = block.end() - chunkStart;

                if (expandedSize <= maxChars) {
                    chunkEnd = block.end();
                    currentSize = expandedSize;
                    next++;
                    continue;
                }

                if (currentSize < minChars) {
                    chunkEnd = block.end();
                    next++;
                }
                break;
            }

            ranges.add(new ChunkRange(chunkStart, chunkEnd, section.sectionPath()));
            index = next;
        }

        if (ranges.size() >= 2) {
            ChunkRange last = ranges.get(ranges.size() - 1);
            if (last.end() - last.start() < Math.min(minChars, targetChars / 2)) {
                ChunkRange previous = ranges.get(ranges.size() - 2);
                if (last.end() - previous.start() <= maxChars * 2) {
                    ranges.set(ranges.size() - 2, new ChunkRange(previous.start(), last.end(), previous.sectionPath()));
                    ranges.remove(ranges.size() - 1);
                }
            }
        }

        return ranges;
    }

    /**
     * Converts raw markdown into structural blocks that can be packed without splitting code
     * fences, headings, or atomic media/link rows.
     */
    private List<Block> segmentToBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        int length = text.length();
        int position = 0;

        boolean inFence = false;
        int fenceStart = -1;
        boolean inParagraph = false;
        int paragraphStart = -1;

        while (position < length) {
            int lineEnd = indexOfNewline(text, position);
            int lineEndWithNewline = lineEnd < length && text.charAt(lineEnd) == '\n' ? lineEnd + 1 : lineEnd;
            String line = text.substring(position, lineEnd);
            String trimmed = trimRight(line);

            if (!inFence && CODE_FENCE.matcher(trimmed).matches()) {
                if (inParagraph) {
                    blocks.add(new Block(BlockKind.PARAGRAPH, paragraphStart, position));
                    inParagraph = false;
                }
                inFence = true;
                fenceStart = position;
                position = lineEndWithNewline;
                continue;
            }

            if (inFence) {
                if (CODE_FENCE.matcher(trimmed).matches()) {
                    blocks.add(new Block(BlockKind.CODE, fenceStart, lineEndWithNewline));
                    inFence = false;
                }
                position = lineEndWithNewline;
                continue;
            }

            if (trimmed.isEmpty()) {
                if (inParagraph) {
                    blocks.add(new Block(BlockKind.PARAGRAPH, paragraphStart, position));
                    inParagraph = false;
                }
                position = lineEndWithNewline;
                continue;
            }

            if (HEADING.matcher(trimmed).matches()) {
                if (inParagraph) {
                    blocks.add(new Block(BlockKind.PARAGRAPH, paragraphStart, position));
                    inParagraph = false;
                }
                blocks.add(new Block(BlockKind.HEADING, position, lineEndWithNewline, headingLevel(trimmed), headingTitle(trimmed)));
                position = lineEndWithNewline;
                continue;
            }

            if (ATOMIC_IMAGE.matcher(trimmed).matches() || ATOMIC_LINK.matcher(trimmed).matches()) {
                if (inParagraph) {
                    blocks.add(new Block(BlockKind.PARAGRAPH, paragraphStart, position));
                    inParagraph = false;
                }
                blocks.add(new Block(BlockKind.ATOMIC, position, lineEndWithNewline));
                position = lineEndWithNewline;
                continue;
            }

            if (!inParagraph) {
                inParagraph = true;
                paragraphStart = position;
            }
            position = lineEndWithNewline;
        }

        if (inFence) {
            blocks.add(new Block(BlockKind.CODE, fenceStart, length));
        } else if (inParagraph) {
            blocks.add(new Block(BlockKind.PARAGRAPH, paragraphStart, length));
        }

        return blocks;
    }

    private String updateHeadingPath(List<String> headingPath, Block heading) {
        if (heading.headingLevel() == null || !StringUtils.hasText(heading.headingTitle())) {
            return headingPath.isEmpty() ? null : String.join(" / ", headingPath);
        }
        while (headingPath.size() >= heading.headingLevel()) {
            headingPath.remove(headingPath.size() - 1);
        }
        headingPath.add(heading.headingTitle());
        return String.join(" / ", headingPath);
    }

    private int headingLevel(String headingLine) {
        int level = 0;
        while (level < headingLine.length() && headingLine.charAt(level) == '#') {
            level++;
        }
        return level;
    }

    private String headingTitle(String headingLine) {
        return headingLine.substring(headingLevel(headingLine)).trim();
    }

    private int indexOfNewline(String text, int fromIndex) {
        int newline = text.indexOf('\n', fromIndex);
        return newline < 0 ? text.length() : newline;
    }

    private String trimRight(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1)) && text.charAt(end - 1) != '\n' && text.charAt(end - 1) != '\r') {
            end--;
        }
        return text.substring(0, end);
    }

    private String tailByChars(String text, int count) {
        if (count <= 0 || text.length() <= count) {
            return text;
        }
        return text.substring(text.length() - count);
    }

    private String mergeSectionPath(String leftMetadata, String rightMetadata) {
        String leftPath = metadataString(leftMetadata, "sectionPath");
        if (StringUtils.hasText(leftPath)) {
            return leftPath;
        }
        String rightPath = metadataString(rightMetadata, "sectionPath");
        return StringUtils.hasText(rightPath) ? rightPath : null;
    }

    private String metadataString(String metadataJson, String key) {
        try {
            Map<String, Object> metadata = parseMetadata(metadataJson);
            Object value = metadata.get(key);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private enum BlockKind {
        HEADING,
        CODE,
        ATOMIC,
        PARAGRAPH
    }

    private record Block(BlockKind kind, int start, int end, Integer headingLevel, String headingTitle) {
        private Block(BlockKind kind, int start, int end) {
            this(kind, start, end, null, null);
        }
    }

    private record Section(List<Block> blocks, String sectionPath) {
    }

    private record ChunkRange(int start, int end, String sectionPath) {
    }
}
