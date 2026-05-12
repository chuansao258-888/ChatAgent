package com.yulong.chatagent.rag.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import com.yulong.chatagent.rag.parser.ParseSegment;
import com.yulong.chatagent.rag.parser.SegmentType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentAwareChunkerRouterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TableAwareChunker defaultTableChunker() {
        TableAwareChunker chunker = new TableAwareChunker(objectMapper);
        ReflectionTestUtils.setField(chunker, "maxRowsPerChunk", 50);
        ReflectionTestUtils.setField(chunker, "overlapRows", 2);
        ReflectionTestUtils.setField(chunker, "maxChars", 3000);
        return chunker;
    }

    @Test
    void shouldChunkMultiPageMarkdownAsLogicalDocumentAndMapPagesBack() throws Exception {
        StructureAwareMarkdownChunker markdownChunker = new StructureAwareMarkdownChunker(objectMapper);
        ReflectionTestUtils.setField(markdownChunker, "targetChars", 80);
        ReflectionTestUtils.setField(markdownChunker, "maxChars", 140);
        ReflectionTestUtils.setField(markdownChunker, "minChars", 1);
        ReflectionTestUtils.setField(markdownChunker, "overlapChars", 0);

        PlainTextChunker plainTextChunker = new PlainTextChunker(objectMapper);
        ReflectionTestUtils.setField(plainTextChunker, "targetChars", 200);
        ReflectionTestUtils.setField(plainTextChunker, "maxChars", 260);
        ReflectionTestUtils.setField(plainTextChunker, "minChars", 1);
        ReflectionTestUtils.setField(plainTextChunker, "overlapChars", 0);

        SegmentAwareChunkerRouter router = new SegmentAwareChunkerRouter(
                markdownChunker,
                plainTextChunker,
                defaultTableChunker(),
                objectMapper
        );
        ReflectionTestUtils.setField(router, "pageTargetChars", 120);
        ReflectionTestUtils.setField(router, "pageMaxChars", 160);

        List<ParseSegment> segments = List.of(
                new ParseSegment(
                        "# Report\n\n## Results\n\nIntro paragraph.",
                        0,
                        SegmentType.PAGE,
                        Map.of("pageNumber", 1)
                ),
                new ParseSegment(
                        "### Accuracy\n\nAccuracy details and benchmark notes.",
                        1,
                        SegmentType.PAGE,
                        Map.of("pageNumber", 2)
                ),
                new ParseSegment(
                        "### Latency\n\nLatency details and rollout notes.",
                        2,
                        SegmentType.PAGE,
                        Map.of("pageNumber", 3)
                )
        );

        List<KnowledgeChunkDraft> drafts = router.chunk(segments);

        assertThat(drafts).hasSizeGreaterThanOrEqualTo(2);
        JsonNode accuracyMetadata = drafts.stream()
                .filter(draft -> draft.content().contains("Accuracy details"))
                .findFirst()
                .map(KnowledgeChunkDraft::metadata)
                .map(metadata -> {
                    try {
                        return objectMapper.readTree(metadata);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .orElseThrow();
        assertThat(accuracyMetadata.get("chunkStrategy").asText()).isEqualTo("markdown_ast");
        assertThat(accuracyMetadata.get("logicalPageMarkdown").asBoolean()).isTrue();
        assertThat(accuracyMetadata.get("sectionPath").asText()).isEqualTo("Report / Results / Accuracy");
        assertThat(accuracyMetadata.get("pageStart").asInt()).isEqualTo(2);
        assertThat(accuracyMetadata.get("pageEnd").asInt()).isEqualTo(2);
        assertThat(accuracyMetadata.get("sourcePages").get(0).asInt()).isEqualTo(2);
    }

    @Test
    void shouldMapMarkdownChunkAcrossPagesWhenSectionContinuesPastPageBoundary() throws Exception {
        StructureAwareMarkdownChunker markdownChunker = new StructureAwareMarkdownChunker(objectMapper);
        ReflectionTestUtils.setField(markdownChunker, "targetChars", 300);
        ReflectionTestUtils.setField(markdownChunker, "maxChars", 400);
        ReflectionTestUtils.setField(markdownChunker, "minChars", 1);
        ReflectionTestUtils.setField(markdownChunker, "overlapChars", 0);

        PlainTextChunker plainTextChunker = new PlainTextChunker(objectMapper);
        ReflectionTestUtils.setField(plainTextChunker, "targetChars", 200);
        ReflectionTestUtils.setField(plainTextChunker, "maxChars", 260);
        ReflectionTestUtils.setField(plainTextChunker, "minChars", 1);
        ReflectionTestUtils.setField(plainTextChunker, "overlapChars", 0);

        SegmentAwareChunkerRouter router = new SegmentAwareChunkerRouter(
                markdownChunker,
                plainTextChunker,
                defaultTableChunker(),
                objectMapper
        );
        ReflectionTestUtils.setField(router, "pageTargetChars", 120);
        ReflectionTestUtils.setField(router, "pageMaxChars", 160);

        List<ParseSegment> segments = List.of(
                new ParseSegment(
                        "# Guide\n\n## Setup\n\nFirst setup paragraph.",
                        0,
                        SegmentType.PAGE,
                        Map.of("pageNumber", 1)
                ),
                new ParseSegment(
                        "Second setup paragraph continues the same section.\n\n## Usage\n\nUsage starts here.",
                        1,
                        SegmentType.PAGE,
                        Map.of("pageNumber", 2)
                )
        );

        List<KnowledgeChunkDraft> drafts = router.chunk(segments);

        JsonNode setupMetadata = drafts.stream()
                .filter(draft -> draft.content().contains("Second setup paragraph"))
                .findFirst()
                .map(KnowledgeChunkDraft::metadata)
                .map(metadata -> {
                    try {
                        return objectMapper.readTree(metadata);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .orElseThrow();
        assertThat(setupMetadata.get("sectionPath").asText()).isEqualTo("Guide / Setup");
        assertThat(setupMetadata.get("pageStart").asInt()).isEqualTo(1);
        assertThat(setupMetadata.get("pageEnd").asInt()).isEqualTo(2);
    }

    @Test
    void shouldTreatMinerUPagesAsLogicalMarkdownWithoutShapeGuessing() throws Exception {
        StructureAwareMarkdownChunker markdownChunker = new StructureAwareMarkdownChunker(objectMapper);
        ReflectionTestUtils.setField(markdownChunker, "targetChars", 80);
        ReflectionTestUtils.setField(markdownChunker, "maxChars", 140);
        ReflectionTestUtils.setField(markdownChunker, "minChars", 1);
        ReflectionTestUtils.setField(markdownChunker, "overlapChars", 0);

        PlainTextChunker plainTextChunker = new PlainTextChunker(objectMapper);
        ReflectionTestUtils.setField(plainTextChunker, "targetChars", 200);
        ReflectionTestUtils.setField(plainTextChunker, "maxChars", 260);
        ReflectionTestUtils.setField(plainTextChunker, "minChars", 1);
        ReflectionTestUtils.setField(plainTextChunker, "overlapChars", 0);

        SegmentAwareChunkerRouter router = new SegmentAwareChunkerRouter(
                markdownChunker,
                plainTextChunker,
                defaultTableChunker(),
                objectMapper
        );
        ReflectionTestUtils.setField(router, "pageTargetChars", 400);
        ReflectionTestUtils.setField(router, "pageMaxChars", 500);

        List<ParseSegment> segments = List.of(
                new ParseSegment(
                        "Title\n\nPlain-looking MinerU markdown page one.",
                        0,
                        SegmentType.PAGE,
                        Map.of("pageNumber", 1, "engineId", "mineru")
                ),
                new ParseSegment(
                        "Plain-looking MinerU markdown page two.",
                        1,
                        SegmentType.PAGE,
                        Map.of("pageNumber", 2, "engineId", "mineru")
                )
        );

        List<KnowledgeChunkDraft> drafts = router.chunk(segments);

        assertThat(drafts).hasSize(1);
        JsonNode metadata = objectMapper.readTree(drafts.get(0).metadata());
        assertThat(metadata.get("chunkStrategy").asText()).isEqualTo("markdown_ast");
        assertThat(metadata.get("logicalPageMarkdown").asBoolean()).isTrue();
        assertThat(metadata.get("pageStart").asInt()).isEqualTo(1);
        assertThat(metadata.get("pageEnd").asInt()).isEqualTo(2);
    }

    @Test
    void shouldUseMarkdownChunkerForFullMarkdownSegmentMetadata() throws Exception {
        StructureAwareMarkdownChunker markdownChunker = new StructureAwareMarkdownChunker(objectMapper);
        ReflectionTestUtils.setField(markdownChunker, "targetChars", 120);
        ReflectionTestUtils.setField(markdownChunker, "maxChars", 200);
        ReflectionTestUtils.setField(markdownChunker, "minChars", 1);
        ReflectionTestUtils.setField(markdownChunker, "overlapChars", 0);

        PlainTextChunker plainTextChunker = new PlainTextChunker(objectMapper);
        ReflectionTestUtils.setField(plainTextChunker, "targetChars", 200);
        ReflectionTestUtils.setField(plainTextChunker, "maxChars", 260);
        ReflectionTestUtils.setField(plainTextChunker, "minChars", 1);
        ReflectionTestUtils.setField(plainTextChunker, "overlapChars", 0);

        SegmentAwareChunkerRouter router = new SegmentAwareChunkerRouter(
                markdownChunker,
                plainTextChunker,
                defaultTableChunker(),
                objectMapper
        );

        List<KnowledgeChunkDraft> drafts = router.chunk(List.of(new ParseSegment(
                "Plain-looking markdown note without headings.",
                0,
                SegmentType.FULL,
                Map.of("contentFormat", "MARKDOWN", "parserType", "markdown")
        )));

        assertThat(drafts).hasSize(1);
        JsonNode metadata = objectMapper.readTree(drafts.get(0).metadata());
        assertThat(metadata.get("chunkStrategy").asText()).isEqualTo("markdown_ast");
    }

    @Test
    void shouldUseMarkdownChunkerForStructuredPdfPageMetadata() throws Exception {
        StructureAwareMarkdownChunker markdownChunker = new StructureAwareMarkdownChunker(objectMapper);
        ReflectionTestUtils.setField(markdownChunker, "targetChars", 120);
        ReflectionTestUtils.setField(markdownChunker, "maxChars", 200);
        ReflectionTestUtils.setField(markdownChunker, "minChars", 1);
        ReflectionTestUtils.setField(markdownChunker, "overlapChars", 0);

        PlainTextChunker plainTextChunker = new PlainTextChunker(objectMapper);
        ReflectionTestUtils.setField(plainTextChunker, "targetChars", 200);
        ReflectionTestUtils.setField(plainTextChunker, "maxChars", 260);
        ReflectionTestUtils.setField(plainTextChunker, "minChars", 1);
        ReflectionTestUtils.setField(plainTextChunker, "overlapChars", 0);

        SegmentAwareChunkerRouter router = new SegmentAwareChunkerRouter(
                markdownChunker,
                plainTextChunker,
                defaultTableChunker(),
                objectMapper
        );

        List<KnowledgeChunkDraft> drafts = router.chunk(List.of(new ParseSegment(
                "# PDF Policy\n\nPDF native structured content.",
                0,
                SegmentType.PAGE,
                Map.of(
                        "pageNumber", 1,
                        "contentOrigin", "NATIVE",
                        "contentFormat", "MARKDOWN",
                        "fontAwareStructureRestored", true,
                        "restoredHeadingCount", 1
                )
        )));

        assertThat(drafts).hasSize(1);
        JsonNode metadata = objectMapper.readTree(drafts.get(0).metadata());
        assertThat(metadata.get("chunkStrategy").asText()).isEqualTo("markdown_ast");
        assertThat(metadata.get("logicalPageMarkdown").asBoolean()).isTrue();
        assertThat(metadata.get("sectionPath").asText()).isEqualTo("PDF Policy");
        assertThat(metadata.get("pageStart").asInt()).isEqualTo(1);
        assertThat(metadata.get("pageEnd").asInt()).isEqualTo(1);
    }

    @Test
    void shouldUseMarkdownChunkerForSingleVdpPdfPage() throws Exception {
        StructureAwareMarkdownChunker markdownChunker = new StructureAwareMarkdownChunker(objectMapper);
        ReflectionTestUtils.setField(markdownChunker, "targetChars", 120);
        ReflectionTestUtils.setField(markdownChunker, "maxChars", 200);
        ReflectionTestUtils.setField(markdownChunker, "minChars", 1);
        ReflectionTestUtils.setField(markdownChunker, "overlapChars", 0);

        PlainTextChunker plainTextChunker = new PlainTextChunker(objectMapper);
        ReflectionTestUtils.setField(plainTextChunker, "targetChars", 200);
        ReflectionTestUtils.setField(plainTextChunker, "maxChars", 260);
        ReflectionTestUtils.setField(plainTextChunker, "minChars", 1);
        ReflectionTestUtils.setField(plainTextChunker, "overlapChars", 0);

        SegmentAwareChunkerRouter router = new SegmentAwareChunkerRouter(
                markdownChunker,
                plainTextChunker,
                defaultTableChunker(),
                objectMapper
        );

        List<KnowledgeChunkDraft> drafts = router.chunk(List.of(new ParseSegment(
                "Plain-looking VDP markdown content.",
                0,
                SegmentType.PAGE,
                Map.of(
                        "pageNumber", 1,
                        "contentOrigin", "VDP_TRANSCRIBED",
                        "contentFormat", "MARKDOWN",
                        "engineId", "vlm",
                        "visualType", "IMAGE"
                )
        )));

        assertThat(drafts).hasSize(1);
        JsonNode metadata = objectMapper.readTree(drafts.get(0).metadata());
        assertThat(metadata.get("chunkStrategy").asText()).isEqualTo("markdown_ast");
        assertThat(metadata.get("logicalPageMarkdown").asBoolean()).isTrue();
        assertThat(metadata.get("pageStart").asInt()).isEqualTo(1);
        assertThat(metadata.get("engineId").asText()).isEqualTo("vlm");
    }

    @Test
    void shouldPreservePageRangeMetadataWhenChunkingPageSegments() throws Exception {
        StructureAwareMarkdownChunker markdownChunker = new StructureAwareMarkdownChunker(objectMapper);
        ReflectionTestUtils.setField(markdownChunker, "targetChars", 120);
        ReflectionTestUtils.setField(markdownChunker, "maxChars", 200);
        ReflectionTestUtils.setField(markdownChunker, "minChars", 1);
        ReflectionTestUtils.setField(markdownChunker, "overlapChars", 0);

        PlainTextChunker plainTextChunker = new PlainTextChunker(objectMapper);
        ReflectionTestUtils.setField(plainTextChunker, "targetChars", 200);
        ReflectionTestUtils.setField(plainTextChunker, "maxChars", 260);
        ReflectionTestUtils.setField(plainTextChunker, "minChars", 1);
        ReflectionTestUtils.setField(plainTextChunker, "overlapChars", 0);

        SegmentAwareChunkerRouter router = new SegmentAwareChunkerRouter(
                markdownChunker,
                plainTextChunker,
                defaultTableChunker(),
                objectMapper
        );
        ReflectionTestUtils.setField(router, "pageTargetChars", 400);
        ReflectionTestUtils.setField(router, "pageMaxChars", 500);

        List<ParseSegment> segments = List.of(
                new ParseSegment(
                        "Page 1 covers onboarding rules and account setup for employees.",
                        0,
                        SegmentType.PAGE,
                        Map.of("pageNumber", 1)
                ),
                new ParseSegment(
                        "Page 2 explains leave policy, approvals, and handoff expectations for managers.",
                        1,
                        SegmentType.PAGE,
                        Map.of("pageNumber", 2)
                )
        );

        List<KnowledgeChunkDraft> drafts = router.chunk(segments);

        assertThat(drafts).hasSize(1);
        JsonNode metadata = objectMapper.readTree(drafts.get(0).metadata());
        assertThat(metadata.get("chunkStrategy").asText()).isEqualTo("page_aware_segment");
        assertThat(metadata.get("pageStart").asInt()).isEqualTo(1);
        assertThat(metadata.get("pageEnd").asInt()).isEqualTo(2);
        assertThat(metadata.get("sourcePages")).hasSize(2);
    }

    @Test
    void shouldChunkFigureSegmentsIndependentlyWithVisualMetadata() throws Exception {
        StructureAwareMarkdownChunker markdownChunker = new StructureAwareMarkdownChunker(objectMapper);
        ReflectionTestUtils.setField(markdownChunker, "targetChars", 120);
        ReflectionTestUtils.setField(markdownChunker, "maxChars", 200);
        ReflectionTestUtils.setField(markdownChunker, "minChars", 1);
        ReflectionTestUtils.setField(markdownChunker, "overlapChars", 0);

        PlainTextChunker plainTextChunker = new PlainTextChunker(objectMapper);
        ReflectionTestUtils.setField(plainTextChunker, "targetChars", 200);
        ReflectionTestUtils.setField(plainTextChunker, "maxChars", 260);
        ReflectionTestUtils.setField(plainTextChunker, "minChars", 1);
        ReflectionTestUtils.setField(plainTextChunker, "overlapChars", 0);

        SegmentAwareChunkerRouter router = new SegmentAwareChunkerRouter(
                markdownChunker,
                plainTextChunker,
                defaultTableChunker(),
                objectMapper
        );
        ReflectionTestUtils.setField(router, "pageTargetChars", 400);
        ReflectionTestUtils.setField(router, "pageMaxChars", 500);

        List<ParseSegment> segments = List.of(
                new ParseSegment(
                        "| Item | Value |\n|---|---|\n| A | 1 |",
                        0,
                        SegmentType.FIGURE,
                        Map.of(
                                "visualType", "TABLE",
                                "contentOrigin", "VDP_TRANSCRIBED",
                                "interpretiveNote", "Simple one-row table"
                        )
                )
        );

        List<KnowledgeChunkDraft> drafts = router.chunk(segments);

        assertThat(drafts).hasSize(1);
        JsonNode metadata = objectMapper.readTree(drafts.get(0).metadata());
        assertThat(metadata.get("sourceSegmentType").asText()).isEqualTo(SegmentType.FIGURE.name());
        assertThat(metadata.get("visualType").asText()).isEqualTo("TABLE");
        assertThat(metadata.get("contentOrigin").asText()).isEqualTo("VDP_TRANSCRIBED");
        assertThat(metadata.get("interpretiveNote").asText()).isEqualTo("Simple one-row table");
    }

    @Test
    void shouldChunkAllStandaloneFigureSegmentsInsteadOfDroppingLaterOnes() throws Exception {
        StructureAwareMarkdownChunker markdownChunker = new StructureAwareMarkdownChunker(objectMapper);
        ReflectionTestUtils.setField(markdownChunker, "targetChars", 120);
        ReflectionTestUtils.setField(markdownChunker, "maxChars", 200);
        ReflectionTestUtils.setField(markdownChunker, "minChars", 1);
        ReflectionTestUtils.setField(markdownChunker, "overlapChars", 0);

        PlainTextChunker plainTextChunker = new PlainTextChunker(objectMapper);
        ReflectionTestUtils.setField(plainTextChunker, "targetChars", 200);
        ReflectionTestUtils.setField(plainTextChunker, "maxChars", 260);
        ReflectionTestUtils.setField(plainTextChunker, "minChars", 1);
        ReflectionTestUtils.setField(plainTextChunker, "overlapChars", 0);

        SegmentAwareChunkerRouter router = new SegmentAwareChunkerRouter(
                markdownChunker,
                plainTextChunker,
                defaultTableChunker(),
                objectMapper
        );
        ReflectionTestUtils.setField(router, "pageTargetChars", 400);
        ReflectionTestUtils.setField(router, "pageMaxChars", 500);

        List<ParseSegment> segments = List.of(
                new ParseSegment(
                        "Figure one text",
                        0,
                        SegmentType.FIGURE,
                        Map.of("visualType", "IMAGE", "pageNumber", 1)
                ),
                new ParseSegment(
                        "Figure two text",
                        1,
                        SegmentType.FIGURE,
                        Map.of("visualType", "CHART", "pageNumber", 2)
                )
        );

        List<KnowledgeChunkDraft> drafts = router.chunk(segments);

        assertThat(drafts).hasSize(2);
        JsonNode firstMetadata = objectMapper.readTree(drafts.get(0).metadata());
        JsonNode secondMetadata = objectMapper.readTree(drafts.get(1).metadata());
        assertThat(firstMetadata.get("visualType").asText()).isEqualTo("IMAGE");
        assertThat(secondMetadata.get("visualType").asText()).isEqualTo("CHART");
        assertThat(firstMetadata.get("chunkIndex").asInt()).isEqualTo(0);
        assertThat(secondMetadata.get("chunkIndex").asInt()).isEqualTo(1);
    }

    @Test
    void shouldTreatPageLevelTableAsStandaloneVisual() throws Exception {
        StructureAwareMarkdownChunker markdownChunker = new StructureAwareMarkdownChunker(objectMapper);
        ReflectionTestUtils.setField(markdownChunker, "targetChars", 120);
        ReflectionTestUtils.setField(markdownChunker, "maxChars", 200);
        ReflectionTestUtils.setField(markdownChunker, "minChars", 1);
        ReflectionTestUtils.setField(markdownChunker, "overlapChars", 0);

        PlainTextChunker plainTextChunker = new PlainTextChunker(objectMapper);
        ReflectionTestUtils.setField(plainTextChunker, "targetChars", 200);
        ReflectionTestUtils.setField(plainTextChunker, "maxChars", 260);
        ReflectionTestUtils.setField(plainTextChunker, "minChars", 1);
        ReflectionTestUtils.setField(plainTextChunker, "overlapChars", 0);

        SegmentAwareChunkerRouter router = new SegmentAwareChunkerRouter(
                markdownChunker,
                plainTextChunker,
                defaultTableChunker(),
                objectMapper
        );
        ReflectionTestUtils.setField(router, "pageTargetChars", 400);
        ReflectionTestUtils.setField(router, "pageMaxChars", 500);

        List<ParseSegment> segments = List.of(
                new ParseSegment(
                        "Intro paragraph before table.",
                        0,
                        SegmentType.PAGE,
                        Map.of("pageNumber", 1)
                ),
                new ParseSegment(
                        "| Item | Value |\n|---|---|\n| A | 1 |",
                        1,
                        SegmentType.PAGE,
                        Map.of("pageNumber", 1, "visualType", "TABLE", "contentOrigin", "VDP_TRANSCRIBED")
                )
        );

        List<KnowledgeChunkDraft> drafts = router.chunk(segments);

        assertThat(drafts).hasSize(2);
        JsonNode secondMetadata = objectMapper.readTree(drafts.get(1).metadata());
        assertThat(secondMetadata.get("visualType").asText()).isEqualTo("TABLE");
        assertThat(secondMetadata.get("sourceSegmentType").asText()).isEqualTo(SegmentType.PAGE.name());
    }

    @Test
    void shouldRouteSpreadsheetTableToTableAwareChunker() throws Exception {
        StructureAwareMarkdownChunker markdownChunker = new StructureAwareMarkdownChunker(objectMapper);
        ReflectionTestUtils.setField(markdownChunker, "targetChars", 200);
        ReflectionTestUtils.setField(markdownChunker, "maxChars", 300);
        ReflectionTestUtils.setField(markdownChunker, "minChars", 1);
        ReflectionTestUtils.setField(markdownChunker, "overlapChars", 0);

        PlainTextChunker plainTextChunker = new PlainTextChunker(objectMapper);
        ReflectionTestUtils.setField(plainTextChunker, "targetChars", 200);
        ReflectionTestUtils.setField(plainTextChunker, "maxChars", 260);
        ReflectionTestUtils.setField(plainTextChunker, "minChars", 1);
        ReflectionTestUtils.setField(plainTextChunker, "overlapChars", 0);

        TableAwareChunker tableChunker = new TableAwareChunker(objectMapper);
        ReflectionTestUtils.setField(tableChunker, "maxRowsPerChunk", 50);
        ReflectionTestUtils.setField(tableChunker, "overlapRows", 0);
        ReflectionTestUtils.setField(tableChunker, "maxChars", 3000);

        SegmentAwareChunkerRouter router = new SegmentAwareChunkerRouter(
                markdownChunker,
                plainTextChunker,
                tableChunker,
                objectMapper
        );

        List<ParseSegment> segments = List.of(
                new ParseSegment(
                        "| Name | Score |\n| --- | --- |\n| Alice | 95 |",
                        0,
                        SegmentType.TABLE,
                        Map.of("docType", "spreadsheet", "sheetName", "Grades", "range", "A1:B2")
                )
        );

        List<KnowledgeChunkDraft> drafts = router.chunk(segments);

        assertThat(drafts).hasSize(1);
        JsonNode metadata = objectMapper.readTree(drafts.get(0).metadata());
        assertThat(metadata.get("chunkStrategy").asText()).isEqualTo("table_row_window");
        assertThat(metadata.get("sheetName").asText()).isEqualTo("Grades");
    }

    @Test
    void shouldRouteNonSpreadsheetTableToPlainTextChunker() throws Exception {
        StructureAwareMarkdownChunker markdownChunker = new StructureAwareMarkdownChunker(objectMapper);
        ReflectionTestUtils.setField(markdownChunker, "targetChars", 200);
        ReflectionTestUtils.setField(markdownChunker, "maxChars", 300);
        ReflectionTestUtils.setField(markdownChunker, "minChars", 1);
        ReflectionTestUtils.setField(markdownChunker, "overlapChars", 0);

        PlainTextChunker plainTextChunker = new PlainTextChunker(objectMapper);
        ReflectionTestUtils.setField(plainTextChunker, "targetChars", 200);
        ReflectionTestUtils.setField(plainTextChunker, "maxChars", 260);
        ReflectionTestUtils.setField(plainTextChunker, "minChars", 1);
        ReflectionTestUtils.setField(plainTextChunker, "overlapChars", 0);

        SegmentAwareChunkerRouter router = new SegmentAwareChunkerRouter(
                markdownChunker,
                plainTextChunker,
                defaultTableChunker(),
                objectMapper
        );

        List<ParseSegment> segments = List.of(
                new ParseSegment(
                        "Some table text without spreadsheet metadata",
                        0,
                        SegmentType.TABLE,
                        Map.of()
                )
        );

        List<KnowledgeChunkDraft> drafts = router.chunk(segments);

        assertThat(drafts).hasSize(1);
        JsonNode metadata = objectMapper.readTree(drafts.get(0).metadata());
        assertThat(metadata.get("chunkStrategy").asText()).isEqualTo("plain_text");
    }
}
