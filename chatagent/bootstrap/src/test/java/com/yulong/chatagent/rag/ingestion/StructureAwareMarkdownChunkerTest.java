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
}
