package com.yulong.chatagent.rag.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlainTextChunkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldStoreSourceOffsetsForPlainTextChunks() throws Exception {
        PlainTextChunker chunker = new PlainTextChunker(objectMapper);
        ReflectionTestUtils.setField(chunker, "targetChars", 18);
        ReflectionTestUtils.setField(chunker, "maxChars", 18);
        ReflectionTestUtils.setField(chunker, "minChars", 1);
        ReflectionTestUtils.setField(chunker, "overlapChars", 0);

        String text = "alpha beta gamma delta epsilon";
        List<KnowledgeChunkDraft> drafts = chunker.chunk(text);

        assertThat(drafts).hasSizeGreaterThan(1);
        for (KnowledgeChunkDraft draft : drafts) {
            JsonNode metadata = objectMapper.readTree(draft.metadata());
            int sourceStart = metadata.get("sourceStart").asInt();
            int sourceEnd = metadata.get("sourceEnd").asInt();

            assertThat(metadata.get("chunkStrategy").asText()).isEqualTo("plain_text");
            assertThat(sourceStart).isLessThan(sourceEnd);
            assertThat(text.substring(sourceStart, sourceEnd).trim()).isEqualTo(draft.content());
        }
    }

    @Test
    void shouldUseMaxCharsAsPlainTextHardLimit() {
        PlainTextChunker chunker = new PlainTextChunker(objectMapper);
        ReflectionTestUtils.setField(chunker, "targetChars", 50);
        ReflectionTestUtils.setField(chunker, "maxChars", 12);
        ReflectionTestUtils.setField(chunker, "minChars", 1);
        ReflectionTestUtils.setField(chunker, "overlapChars", 0);

        List<KnowledgeChunkDraft> drafts = chunker.chunk("alpha beta gamma delta");

        assertThat(drafts).isNotEmpty();
        assertThat(drafts)
                .allSatisfy(draft -> assertThat(draft.content().length()).isLessThanOrEqualTo(12));
    }
}
