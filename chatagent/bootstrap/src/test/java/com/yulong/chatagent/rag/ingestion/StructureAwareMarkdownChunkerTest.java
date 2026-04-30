package com.yulong.chatagent.rag.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructureAwareMarkdownChunkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldCaptureNestedHeadingPathInChunkMetadata() throws Exception {
        StructureAwareMarkdownChunker chunker = new StructureAwareMarkdownChunker(objectMapper);
        ReflectionTestUtils.setField(chunker, "targetChars", 120);
        ReflectionTestUtils.setField(chunker, "maxChars", 200);
        ReflectionTestUtils.setField(chunker, "minChars", 1);
        ReflectionTestUtils.setField(chunker, "overlapChars", 0);

        List<KnowledgeChunkDraft> drafts = chunker.chunk("""
                # HR
                Company policies overview covers onboarding expectations, payroll timing, conduct rules, and manager responsibilities.

                ## Leave
                Employees receive 14 days of annual leave, must submit requests in advance, and should coordinate team coverage before approval.
                """);

        assertThat(drafts).hasSize(2);
        JsonNode firstMetadata = objectMapper.readTree(drafts.get(0).metadata());
        JsonNode secondMetadata = objectMapper.readTree(drafts.get(1).metadata());

        assertThat(firstMetadata.get("sectionPath").asText()).isEqualTo("HR");
        assertThat(secondMetadata.get("sectionPath").asText()).isEqualTo("HR / Leave");
    }

    @Test
    void shouldStoreStructuredHeadingMetadataWithoutChangingContent() throws Exception {
        StructureAwareMarkdownChunker chunker = newChunker(120, 220, 1);

        List<KnowledgeChunkDraft> drafts = chunker.chunk("""
                # Platform / Ops

                ## Reliability

                ### Retry Policy

                Retry policy content explains backoff, timeout budgets, and transient failure handling.
                """);

        KnowledgeChunkDraft retryDraft = drafts.get(drafts.size() - 1);
        JsonNode metadata = objectMapper.readTree(retryDraft.metadata());

        assertThat(metadata.get("sectionPath").asText()).isEqualTo("Platform / Ops / Reliability / Retry Policy");
        assertThat(metadata.get("sectionTitle").asText()).isEqualTo("Retry Policy");
        assertThat(metadata.get("sectionLevel").asInt()).isEqualTo(3);
        assertThat(metadata.get("sectionHeadings")).hasSize(3);
        assertThat(metadata.get("sectionHeadings").get(0).get("title").asText()).isEqualTo("Platform / Ops");
        assertThat(metadata.get("sectionHeadings").get(0).get("level").asInt()).isEqualTo(1);
        assertThat(retryDraft.content()).doesNotStartWith("Section:");
    }

    @Test
    void shouldRecognizeCodeFenceAndTableBlockKinds() throws Exception {
        StructureAwareMarkdownChunker chunker = newChunker(400, 800, 1);

        List<KnowledgeChunkDraft> drafts = chunker.chunk("""
                # Developer Guide

                ```java
                class Example {
                    void run() {}
                }
                ```

                | Name | Value |
                |---|---|
                | timeout | 30s |
                """);

        JsonNode metadata = objectMapper.readTree(drafts.get(0).metadata());

        assertThat(drafts).hasSize(1);
        assertThat(metadata.get("blockKinds").toString()).contains("code_block", "table");
        assertThat(drafts.get(0).content()).contains("```java", "class Example", "| timeout | 30s |");
    }

    @Test
    void shouldMarkOversizedParagraphSplits() throws Exception {
        StructureAwareMarkdownChunker chunker = newChunker(60, 80, 1);
        String longParagraph = "alpha ".repeat(80);

        List<KnowledgeChunkDraft> drafts = chunker.chunk("""
                # Notes

                %s
                """.formatted(longParagraph));

        assertThat(drafts).hasSizeGreaterThan(1);
        boolean hasSplitMarker = false;
        for (KnowledgeChunkDraft draft : drafts) {
            JsonNode metadata = objectMapper.readTree(draft.metadata());
            hasSplitMarker = hasSplitMarker || metadata.path("splitOversizedBlock").asBoolean(false);
        }
        assertThat(hasSplitMarker).isTrue();
    }

    private StructureAwareMarkdownChunker newChunker(int targetChars, int maxChars, int minChars) {
        StructureAwareMarkdownChunker chunker = new StructureAwareMarkdownChunker(objectMapper);
        ReflectionTestUtils.setField(chunker, "targetChars", targetChars);
        ReflectionTestUtils.setField(chunker, "maxChars", maxChars);
        ReflectionTestUtils.setField(chunker, "minChars", minChars);
        ReflectionTestUtils.setField(chunker, "overlapChars", 0);
        return chunker;
    }
}
