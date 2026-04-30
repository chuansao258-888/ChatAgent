package com.yulong.chatagent.rag.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Markdown chunker backed by flexmark AST. It keeps original Markdown as chunk content,
 * and stores structured heading metadata for index-time retrieval text assembly.
 */
@Component
@RequiredArgsConstructor
public class StructureAwareMarkdownChunker {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final Parser MARKDOWN_PARSER = Parser.builder(new MutableDataSet()
            .set(Parser.EXTENSIONS, List.of(TablesExtension.create())))
            .build();

    private final ObjectMapper objectMapper;

    @Value("${chatagent.rag.chunk.markdown.target-chars:1400}")
    private int targetChars;

    @Value("${chatagent.rag.chunk.markdown.max-chars:1800}")
    private int maxChars;

    @Value("${chatagent.rag.chunk.markdown.min-chars:600}")
    private int minChars;

    @Value("${chatagent.rag.chunk.markdown.overlap-chars:0}")
    private int overlapChars;

    public List<KnowledgeChunkDraft> chunk(String markdownText) {
        if (!StringUtils.hasText(markdownText)) {
            return List.of();
        }

        String normalizedMarkdown = normalizeLineEndings(markdownText);
        List<MarkdownBlock> blocks = parseBlocks(normalizedMarkdown);
        if (blocks.isEmpty()) {
            return List.of(buildDraft(
                    normalizedMarkdown.trim(),
                    List.of("paragraph"),
                    0,
                    List.of(),
                    Map.of()
            ));
        }

        List<ChunkSlice> slices = packBlocksToChunks(blocks, normalizedMarkdown);
        List<KnowledgeChunkDraft> drafts = new ArrayList<>();
        String overlapPrefix = "";

        for (int index = 0; index < slices.size(); index++) {
            ChunkSlice slice = slices.get(index);
            String body = normalizedMarkdown.substring(slice.start(), slice.end()).trim();
            if (!StringUtils.hasText(body)) {
                continue;
            }
            String content = overlapPrefix.isEmpty() ? body : overlapPrefix + body;
            List<HeadingInfo> headings = mostSpecificHeadings(slice.blocks());
            List<String> blockKinds = collectBlockKinds(slice.blocks());
            Map<String, Object> extraMetadata = slice.extraMetadata();
            drafts.add(buildDraft(content, blockKinds, index, headings, extraMetadata));
            overlapPrefix = overlapChars > 0 ? tailByChars(body, overlapChars) : "";
        }

        return compactSmallDrafts(drafts);
    }

    private String normalizeLineEndings(String markdownText) {
        return markdownText.replace("\r\n", "\n").replace('\r', '\n');
    }

    private List<MarkdownBlock> parseBlocks(String markdownText) {
        Document document = MARKDOWN_PARSER.parse(markdownText);
        List<MarkdownBlock> blocks = new ArrayList<>();
        List<HeadingInfo> headingPath = new ArrayList<>();

        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            int start = Math.max(0, node.getChars().getStartOffset());
            int end = Math.min(markdownText.length(), node.getChars().getEndOffset());
            if (end <= start) {
                continue;
            }

            BlockKind kind = blockKind(node);
            if (kind == BlockKind.HEADING && node instanceof Heading heading) {
                headingPath = updateHeadingPath(headingPath, heading.getLevel(), heading.getText().toString());
            }
            List<HeadingInfo> blockHeadings = List.copyOf(headingPath);
            boolean atomic = kind == BlockKind.CODE_BLOCK
                    || kind == BlockKind.TABLE
                    || kind == BlockKind.LIST
                    || kind == BlockKind.BLOCKQUOTE;
            blocks.add(new MarkdownBlock(kind, start, end, blockHeadings, atomic));
        }

        return blocks;
    }

    private BlockKind blockKind(Node node) {
        if (node instanceof Heading) {
            return BlockKind.HEADING;
        }
        if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
            return BlockKind.CODE_BLOCK;
        }
        if (node instanceof TableBlock) {
            return BlockKind.TABLE;
        }
        if (node instanceof BulletList || node instanceof OrderedList) {
            return BlockKind.LIST;
        }
        if (node instanceof BlockQuote) {
            return BlockKind.BLOCKQUOTE;
        }
        if (node instanceof HtmlBlock) {
            return BlockKind.HTML;
        }
        if (node instanceof ThematicBreak) {
            return BlockKind.THEMATIC_BREAK;
        }
        if (node instanceof Paragraph) {
            return BlockKind.PARAGRAPH;
        }
        return BlockKind.OTHER;
    }

    private List<HeadingInfo> updateHeadingPath(List<HeadingInfo> currentPath, int level, String title) {
        List<HeadingInfo> updated = new ArrayList<>(currentPath == null ? List.of() : currentPath);
        String normalizedTitle = title == null ? "" : title.trim();
        if (level <= 0 || !StringUtils.hasText(normalizedTitle)) {
            return updated;
        }
        while (updated.size() >= level) {
            updated.remove(updated.size() - 1);
        }
        updated.add(new HeadingInfo(level, normalizedTitle));
        return updated;
    }

    private List<ChunkSlice> packBlocksToChunks(List<MarkdownBlock> blocks, String markdownText) {
        List<Section> sections = splitIntoSections(blocks);
        List<ChunkSlice> slices = new ArrayList<>();
        for (Section section : sections) {
            slices.addAll(packSectionBlocks(section, markdownText));
        }
        return slices;
    }

    private List<Section> splitIntoSections(List<MarkdownBlock> blocks) {
        List<Section> sections = new ArrayList<>();
        List<MarkdownBlock> current = new ArrayList<>();
        List<HeadingInfo> currentHeadings = List.of();

        for (MarkdownBlock block : blocks) {
            if (block.kind() == BlockKind.HEADING && !current.isEmpty()) {
                sections.add(new Section(current, currentHeadings));
                current = new ArrayList<>();
            }
            currentHeadings = block.headingPath();
            current.add(block);
        }

        if (!current.isEmpty()) {
            sections.add(new Section(current, currentHeadings));
        }
        return sections;
    }

    private List<ChunkSlice> packSectionBlocks(Section section, String markdownText) {
        List<MarkdownBlock> sectionBlocks = section.blocks();
        List<ChunkSlice> slices = new ArrayList<>();
        int index = 0;

        while (index < sectionBlocks.size()) {
            MarkdownBlock first = sectionBlocks.get(index);
            if (blockLength(first) > maxChars) {
                slices.addAll(splitOversizedBlock(first, markdownText));
                index++;
                continue;
            }

            int chunkStart = first.start();
            int chunkEnd = first.end();
            int currentSize = chunkEnd - chunkStart;
            List<MarkdownBlock> chunkBlocks = new ArrayList<>();
            chunkBlocks.add(first);

            int next = index + 1;
            while (next < sectionBlocks.size()) {
                MarkdownBlock block = sectionBlocks.get(next);
                int expandedSize = block.end() - chunkStart;

                if (expandedSize <= maxChars) {
                    chunkEnd = block.end();
                    currentSize = expandedSize;
                    chunkBlocks.add(block);
                    next++;
                    continue;
                }

                if (currentSize < minChars) {
                    chunkEnd = block.end();
                    chunkBlocks.add(block);
                    next++;
                }
                break;
            }

            slices.add(new ChunkSlice(chunkStart, chunkEnd, List.copyOf(chunkBlocks), Map.of()));
            index = next;
        }

        if (slices.size() >= 2) {
            ChunkSlice last = slices.get(slices.size() - 1);
            if (last.end() - last.start() < Math.min(minChars, targetChars / 2)) {
                ChunkSlice previous = slices.get(slices.size() - 2);
                if (last.end() - previous.start() <= maxChars * 2) {
                    List<MarkdownBlock> mergedBlocks = new ArrayList<>(previous.blocks());
                    mergedBlocks.addAll(last.blocks());
                    slices.set(slices.size() - 2, new ChunkSlice(
                            previous.start(),
                            last.end(),
                            List.copyOf(mergedBlocks),
                            mergeMetadata(previous.extraMetadata(), last.extraMetadata())
                    ));
                    slices.remove(slices.size() - 1);
                }
            }
        }

        return slices;
    }

    private List<ChunkSlice> splitOversizedBlock(MarkdownBlock block, String markdownText) {
        int oversizedLimit = Math.max(maxChars, maxChars * 2);
        if (block.atomic() && blockLength(block) <= oversizedLimit) {
            return List.of(new ChunkSlice(block.start(), block.end(), List.of(block), Map.of()));
        }

        List<ChunkSlice> slices = new ArrayList<>();
        String text = markdownText.substring(block.start(), block.end());
        List<TextRange> ranges = splitTextByNaturalBoundaries(text, maxChars);
        for (TextRange range : ranges) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("splitOversizedBlock", true);
            metadata.put("oversizedBlockKind", block.kind().metadataName());
            slices.add(new ChunkSlice(
                    block.start() + range.start(),
                    block.start() + range.end(),
                    List.of(block),
                    metadata
            ));
        }
        return slices;
    }

    private List<TextRange> splitTextByNaturalBoundaries(String text, int limit) {
        List<TextRange> ranges = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return ranges;
        }
        int start = 0;
        while (start < text.length()) {
            int candidateEnd = Math.min(start + limit, text.length());
            int end = findNaturalEnd(text, start, candidateEnd);
            if (end <= start) {
                end = candidateEnd;
            }
            ranges.add(new TextRange(start, end));
            start = end;
        }
        return ranges;
    }

    private int findNaturalEnd(String text, int start, int candidateEnd) {
        if (candidateEnd >= text.length()) {
            return text.length();
        }
        int minEnd = Math.min(start + Math.max(120, minChars / 2), text.length());
        for (String boundary : List.of("\n\n", "\n", "。", "！", "？", ".", "!", "?")) {
            int pos = text.lastIndexOf(boundary, candidateEnd - 1);
            if (pos >= minEnd) {
                return pos + boundary.length();
            }
        }
        int pos = text.lastIndexOf(' ', candidateEnd - 1);
        return pos >= minEnd ? pos : candidateEnd;
    }

    private KnowledgeChunkDraft buildDraft(String content,
                                           List<String> blockKinds,
                                           int chunkIndex,
                                           List<HeadingInfo> headings,
                                           Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunkStrategy", "markdown_ast");
        metadata.put("blockKinds", blockKinds);
        metadata.put("contentLength", content.length());
        metadata.put("chunkIndex", chunkIndex);
        addHeadingMetadata(metadata, headings);
        if (extraMetadata != null) {
            metadata.putAll(extraMetadata);
        }
        return new KnowledgeChunkDraft(content, writeMetadata(metadata));
    }

    private void addHeadingMetadata(Map<String, Object> metadata, List<HeadingInfo> headings) {
        if (headings == null || headings.isEmpty()) {
            return;
        }
        HeadingInfo leaf = headings.get(headings.size() - 1);
        metadata.put("sectionPath", headingPath(headings, " / "));
        metadata.put("sectionTitle", leaf.title());
        metadata.put("sectionLevel", leaf.level());
        List<Map<String, Object>> structuredHeadings = new ArrayList<>(headings.size());
        for (HeadingInfo heading : headings) {
            structuredHeadings.add(Map.of(
                    "level", heading.level(),
                    "title", heading.title()
            ));
        }
        metadata.put("sectionHeadings", structuredHeadings);
    }

    private String headingPath(List<HeadingInfo> headings, String delimiter) {
        return headings.stream()
                .map(HeadingInfo::title)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + delimiter + right)
                .orElse("");
    }

    private List<HeadingInfo> mostSpecificHeadings(List<MarkdownBlock> blocks) {
        List<HeadingInfo> result = List.of();
        if (blocks == null) {
            return result;
        }
        for (MarkdownBlock block : blocks) {
            if (block.headingPath() != null && block.headingPath().size() >= result.size()) {
                result = block.headingPath();
            }
        }
        return result;
    }

    private List<String> collectBlockKinds(List<MarkdownBlock> blocks) {
        LinkedHashSet<String> kinds = new LinkedHashSet<>();
        if (blocks != null) {
            for (MarkdownBlock block : blocks) {
                kinds.add(block.kind().metadataName());
            }
        }
        return List.copyOf(kinds);
    }

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

    private KnowledgeChunkDraft mergeDrafts(KnowledgeChunkDraft left, KnowledgeChunkDraft right) {
        String mergedContent = left.content().trim() + "\n\n" + right.content().trim();
        List<HeadingInfo> headings = mergeHeadings(left.metadata(), right.metadata());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunkStrategy", "markdown_ast");
        metadata.put("blockKinds", mergeBlockKinds(left.metadata(), right.metadata()));
        metadata.put("contentLength", mergedContent.length());
        metadata.put("mergedSmallChunk", true);
        addHeadingMetadata(metadata, headings);
        return new KnowledgeChunkDraft(mergedContent, writeMetadata(metadata));
    }

    private List<String> mergeBlockKinds(String leftMetadata, String rightMetadata) {
        LinkedHashSet<String> blockKinds = new LinkedHashSet<>();
        addBlockKinds(blockKinds, leftMetadata);
        addBlockKinds(blockKinds, rightMetadata);
        return List.copyOf(blockKinds);
    }

    private void addBlockKinds(LinkedHashSet<String> blockKinds, String metadataJson) {
        try {
            Map<String, Object> metadata = parseMetadata(metadataJson);
            Object value = metadata.get("blockKinds");
            if (value instanceof List<?> values) {
                for (Object item : values) {
                    if (item != null) {
                        blockKinds.add(String.valueOf(item));
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private List<HeadingInfo> mergeHeadings(String leftMetadata, String rightMetadata) {
        List<HeadingInfo> left = parseHeadings(leftMetadata);
        List<HeadingInfo> right = parseHeadings(rightMetadata);
        return right.size() >= left.size() ? right : left;
    }

    private List<HeadingInfo> parseHeadings(String metadataJson) {
        try {
            Map<String, Object> metadata = parseMetadata(metadataJson);
            Object value = metadata.get("sectionHeadings");
            if (!(value instanceof List<?> values)) {
                return List.of();
            }
            List<HeadingInfo> headings = new ArrayList<>();
            for (Object item : values) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Object levelValue = map.get("level");
                Object titleValue = map.get("title");
                if (levelValue instanceof Number number && titleValue != null && StringUtils.hasText(titleValue.toString())) {
                    headings.add(new HeadingInfo(number.intValue(), titleValue.toString()));
                }
            }
            return headings;
        } catch (Exception ignored) {
            return List.of();
        }
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
            metadata.put("chunkStrategy", "markdown_ast");
            metadata.put("contentLength", contentLength(draft));
            metadata.put("chunkIndex", i);
            reindexedDrafts.add(new KnowledgeChunkDraft(
                    draft.content(),
                    writeMetadata(metadata)
            ));
        }
        return reindexedDrafts;
    }

    private int blockLength(MarkdownBlock block) {
        return block == null ? 0 : Math.max(0, block.end() - block.start());
    }

    private int contentLength(KnowledgeChunkDraft draft) {
        return draft == null || draft.content() == null ? 0 : draft.content().length();
    }

    private String tailByChars(String text, int count) {
        if (count <= 0 || text.length() <= count) {
            return text;
        }
        return text.substring(text.length() - count);
    }

    private Map<String, Object> mergeMetadata(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (left != null) {
            merged.putAll(left);
        }
        if (right != null) {
            merged.putAll(right);
        }
        return merged;
    }

    private Map<String, Object> parseMetadata(String metadataJson) throws JsonProcessingException {
        if (!StringUtils.hasText(metadataJson)) {
            return Map.of();
        }
        return objectMapper.readValue(metadataJson, MAP_TYPE);
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize markdown chunk metadata", e);
        }
    }

    private enum BlockKind {
        HEADING("heading"),
        PARAGRAPH("paragraph"),
        LIST("list"),
        TABLE("table"),
        CODE_BLOCK("code_block"),
        BLOCKQUOTE("blockquote"),
        HTML("html"),
        THEMATIC_BREAK("thematic_break"),
        OTHER("other");

        private final String metadataName;

        BlockKind(String metadataName) {
            this.metadataName = metadataName;
        }

        private String metadataName() {
            return metadataName;
        }
    }

    private record HeadingInfo(int level, String title) {
    }

    private record MarkdownBlock(BlockKind kind, int start, int end, List<HeadingInfo> headingPath, boolean atomic) {
    }

    private record Section(List<MarkdownBlock> blocks, List<HeadingInfo> headingPath) {
    }

    private record ChunkSlice(int start, int end, List<MarkdownBlock> blocks, Map<String, Object> extraMetadata) {
    }

    private record TextRange(int start, int end) {
    }
}
