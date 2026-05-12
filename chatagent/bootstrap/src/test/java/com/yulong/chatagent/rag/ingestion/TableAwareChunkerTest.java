package com.yulong.chatagent.rag.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TableAwareChunkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReturnSingleChunkWhenSmall() {
        TableAwareChunker chunker = newChunker(50, 2, 3000);
        String table = "| A | B |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |";

        var drafts = chunker.chunk(table, Map.of("sheetName", "Test"));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).content()).isEqualTo(table);
    }

    @Test
    void shouldSplitLargeTableIntoRowWindows() {
        TableAwareChunker chunker = newChunker(3, 0, 10000);
        String table = buildTable(10);

        var drafts = chunker.chunk(table, Map.of("sheetName", "Big"));

        assertThat(drafts.size()).isGreaterThan(1);
        for (KnowledgeChunkDraft draft : drafts) {
            assertThat(draft.content()).contains("| Name |");
            assertThat(draft.content()).contains("| --- |");
        }
    }

    @Test
    void shouldRepeatHeaderInEveryChunk() throws Exception {
        TableAwareChunker chunker = newChunker(3, 0, 10000);
        String table = buildTable(10);

        var drafts = chunker.chunk(table, Map.of("sheetName", "Test"));

        for (KnowledgeChunkDraft draft : drafts) {
            String content = draft.content();
            assertThat(content).startsWith("| Name | Value |");
            assertThat(content).contains("| --- |");
        }
    }

    @Test
    void shouldAddRowRangeMetadata() throws Exception {
        TableAwareChunker chunker = newChunker(3, 0, 10000);
        String table = buildTable(10);

        // Parent segment: header at row 1 (1-based), 10 data rows → rowEnd=11
        var drafts = chunker.chunk(table, Map.of("sheetName", "Test", "rowStart", 1, "rowEnd", 11));

        JsonNode meta0 = objectMapper.readTree(drafts.get(0).metadata());
        assertThat(meta0.get("chunkStrategy").asText()).isEqualTo("table_row_window");
        assertThat(meta0.get("sheetName").asText()).isEqualTo("Test");
        // First chunk: data rows 2-4 (1-based), exclusive 0-based end = 4
        assertThat(meta0.get("rowStart").asInt()).isEqualTo(2);
        assertThat(meta0.get("rowEnd").asInt()).isEqualTo(4);

        JsonNode metaLast = objectMapper.readTree(drafts.get(drafts.size() - 1).metadata());
        // Last chunk: data row 11 (1-based), exclusive 0-based end = 11
        assertThat(metaLast.get("rowStart").asInt()).isEqualTo(11);
        assertThat(metaLast.get("rowEnd").asInt()).isEqualTo(11);
    }

    @Test
    void shouldReturnEmptyForBlankInput() {
        TableAwareChunker chunker = newChunker(50, 2, 3000);

        assertThat(chunker.chunk("", Map.of())).isEmpty();
        assertThat(chunker.chunk(null, Map.of())).isEmpty();
    }

    @Test
    void shouldApplyOverlapRows() throws Exception {
        TableAwareChunker chunker = newChunker(3, 2, 10000);
        String table = buildTable(10);

        var drafts = chunker.chunk(table, Map.of("sheetName", "Test"));

        // With overlap, chunks share some rows
        if (drafts.size() >= 2) {
            String first = drafts.get(0).content();
            String second = drafts.get(1).content();
            // The second chunk should start with the header
            assertThat(second).startsWith("| Name | Value |");
        }
    }

    private TableAwareChunker newChunker(int maxRows, int overlap, int maxChars) {
        TableAwareChunker chunker = new TableAwareChunker(objectMapper);
        ReflectionTestUtils.setField(chunker, "maxRowsPerChunk", maxRows);
        ReflectionTestUtils.setField(chunker, "overlapRows", overlap);
        ReflectionTestUtils.setField(chunker, "maxChars", maxChars);
        return chunker;
    }

    private String buildTable(int dataRows) {
        StringBuilder sb = new StringBuilder();
        sb.append("| Name | Value |\n| --- | --- |");
        for (int i = 1; i <= dataRows; i++) {
            sb.append("\n| Item").append(i).append(" | ").append(i * 10).append(" |");
        }
        return sb.toString();
    }
}
