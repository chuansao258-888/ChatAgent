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
                    Map.of(
                            "sourceStart", 0,
                            "sourceEnd", normalizedMarkdown.length()
                    )
            ));
        }

        List<KnowledgeChunkDraft> drafts = new ArrayList<>();
        for (Section section : splitIntoSections(blocks)) {
            drafts.addAll(chunkSection(section, normalizedMarkdown));
        }
        return applyOverlap(reindexDrafts(drafts));
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

    private List<KnowledgeChunkDraft> chunkSection(Section section, String markdownText) {
        if (section == null || section.blocks().isEmpty()) {
            return List.of();
        }
        int sectionStart = section.blocks().get(0).start();
        int sectionEnd = section.blocks().get(section.blocks().size() - 1).end();
        if (sectionEnd - sectionStart <= maxChars) {
            return List.of(buildDraftFromRange(
                    markdownText,
                    sectionStart,
                    sectionEnd,
                    section.blocks(),
                    section.headingPath(),
                    Map.of()
            ));
        }

        return splitLongSection(section, markdownText);
    }

    private List<KnowledgeChunkDraft> splitLongSection(Section section, String markdownText) {
        List<MarkdownBlock> sectionBlocks = section.blocks();
        List<KnowledgeChunkDraft> drafts = new ArrayList<>();
        int chunkLimit = fallbackChunkLimit();
        int index = 0;

        while (index < sectionBlocks.size()) {
            MarkdownBlock first = sectionBlocks.get(index);
            if (blockLength(first) > chunkLimit) {
                drafts.addAll(splitOversizedBlock(first, markdownText));
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

                if (expandedSize <= chunkLimit) {
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

            drafts.add(buildDraftFromRange(
                    markdownText,
                    chunkStart,
                    chunkEnd,
                    List.copyOf(chunkBlocks),
                    mostSpecificHeadings(chunkBlocks),
                    Map.of()
            ));
            index = next;
        }
        return drafts;
    }

    private List<KnowledgeChunkDraft> splitOversizedBlock(MarkdownBlock block, String markdownText) {
        int chunkLimit = fallbackChunkLimit();
        int oversizedLimit = Math.max(maxChars, maxChars * 2);
        if (block.atomic() && blockLength(block) <= oversizedLimit) {
            return List.of(buildDraftFromRange(
                    markdownText,
                    block.start(),
                    block.end(),
                    List.of(block),
                    block.headingPath(),
                    Map.of()
            ));
        }

        List<KnowledgeChunkDraft> drafts = new ArrayList<>();
        String text = markdownText.substring(block.start(), block.end());
        List<TextRange> ranges = splitTextByNaturalBoundaries(text, chunkLimit);
        for (TextRange range : ranges) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("splitOversizedBlock", true);
            metadata.put("oversizedBlockKind", block.kind().metadataName());
            drafts.add(buildDraftFromRange(
                    markdownText,
                    block.start() + range.start(),
                    block.start() + range.end(),
                    List.of(block),
                    block.headingPath(),
                    metadata
            ));
        }
        return drafts;
    }

    private int fallbackChunkLimit() {
        int desiredLimit = Math.max(targetChars, minChars);
        return Math.max(1, Math.min(maxChars, desiredLimit));
    }

    private KnowledgeChunkDraft buildDraftFromRange(String markdownText,
                                                    int start,
                                                    int end,
                                                    List<MarkdownBlock> blocks,
                                                    List<HeadingInfo> headings,
                                                    Map<String, Object> extraMetadata) {
        String body = markdownText.substring(start, end).trim();
        Map<String, Object> metadata = new LinkedHashMap<>(extraMetadata == null ? Map.of() : extraMetadata);
        metadata.put("sourceStart", start);
        metadata.put("sourceEnd", end);
        return buildDraft(body, collectBlockKinds(blocks), 0, headings, metadata);
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

    private List<KnowledgeChunkDraft> applyOverlap(List<KnowledgeChunkDraft> drafts) {
        if (overlapChars <= 0 || drafts.size() < 2) {
            return drafts;
        }

        List<KnowledgeChunkDraft> overlappedDrafts = new ArrayList<>(drafts.size());
        String previousOriginalContent = null;
        for (KnowledgeChunkDraft draft : drafts) {
            if (previousOriginalContent == null) {
                overlappedDrafts.add(draft);
                previousOriginalContent = draft.content();
                continue;
            }

            String overlap = tailByChars(previousOriginalContent, overlapChars).trim();
            String content = StringUtils.hasText(overlap)
                    ? overlap + "\n\n" + draft.content()
                    : draft.content();
            Map<String, Object> metadata;
            try {
                metadata = new LinkedHashMap<>(parseMetadata(draft.metadata()));
            } catch (Exception e) {
                metadata = new LinkedHashMap<>();
            }
            metadata.put("contentLength", contentLength(content));
            metadata.put("overlapChars", Math.min(overlapChars, contentLength(previousOriginalContent)));
            overlappedDrafts.add(new KnowledgeChunkDraft(content, writeMetadata(metadata)));
            previousOriginalContent = draft.content();
        }
        return overlappedDrafts;
    }

    private int blockLength(MarkdownBlock block) {
        return block == null ? 0 : Math.max(0, block.end() - block.start());
    }

    private int contentLength(KnowledgeChunkDraft draft) {
        return draft == null || draft.content() == null ? 0 : draft.content().length();
    }

    private int contentLength(String content) {
        return content == null ? 0 : content.length();
    }

    private String tailByChars(String text, int count) {
        if (count <= 0 || text.length() <= count) {
            return text;
        }
        return text.substring(text.length() - count);
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

    private record TextRange(int start, int end) {
    }
}
