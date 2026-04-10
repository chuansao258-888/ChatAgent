package com.yulong.chatagent.rag.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import com.yulong.chatagent.rag.application.MarkdownParserService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownSectionChunkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldBuildChunkWithTitleAndBody() throws Exception {
        MarkdownSectionChunker chunker = new MarkdownSectionChunker(objectMapper);

        List<KnowledgeChunkDraft> drafts = chunker.chunk(List.of(
                new MarkdownParserService.MarkdownSection("Install", "Step one\nStep two")
        ));

        assertThat(drafts).hasSize(1);
        KnowledgeChunkDraft draft = drafts.get(0);
        assertThat(draft.content()).isEqualTo("Install\nStep one\nStep two");
        assertThat(draft.embeddingText()).isEqualTo(draft.content());

        JsonNode metadata = objectMapper.readTree(draft.metadata());
        assertThat(metadata.get("title").asText()).isEqualTo("Install");
        assertThat(metadata.get("hasBody").asBoolean()).isTrue();
        assertThat(metadata.get("contentLength").asInt()).isEqualTo("Step one\nStep two".length());
    }

    @Test
    void shouldSkipSectionsWithoutUsableTitle() {
        MarkdownSectionChunker chunker = new MarkdownSectionChunker(objectMapper);

        List<KnowledgeChunkDraft> drafts = chunker.chunk(List.of(
                new MarkdownParserService.MarkdownSection("   ", "ignored"),
                new MarkdownParserService.MarkdownSection(null, "ignored")
        ));

        assertThat(drafts).isEmpty();
    }
}
