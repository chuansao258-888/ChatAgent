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
}
