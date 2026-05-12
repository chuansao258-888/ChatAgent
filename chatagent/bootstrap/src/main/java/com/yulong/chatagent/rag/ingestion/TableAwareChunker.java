package com.yulong.chatagent.rag.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Table-aware chunker for spreadsheet TABLE segments.
 * Splits row-based content into windows with repeated header rows.
 */
@Component
public class TableAwareChunker {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    @Value("${chatagent.rag.chunk.spreadsheet.max-rows-per-chunk:50}")
    private int maxRowsPerChunk = 50;

    @Value("${chatagent.rag.chunk.spreadsheet.overlap-rows:2}")
    private int overlapRows = 2;

    @Value("${chatagent.rag.chunk.spreadsheet.max-chars:3000}")
    private int maxChars = 3000;

    public TableAwareChunker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<KnowledgeChunkDraft> chunk(String tableText, Map<String, Object> segmentMetadata) {
        if (tableText == null || tableText.isBlank()) {
            return List.of();
        }

        List<String> lines = tableText.lines().toList();
        if (lines.size() <= 2) {
            return List.of(buildDraft(tableText, 0, segmentMetadata));
        }

        String header = lines.get(0);
        String separator = lines.get(1);
        List<String> dataLines = lines.subList(2, lines.size());

        if (dataLines.size() <= maxRowsPerChunk) {
            return List.of(buildDraft(tableText, 0, segmentMetadata));
        }

        List<KnowledgeChunkDraft> drafts = new ArrayList<>();
        int index = 0;
        int start = 0;

        while (start < dataLines.size()) {
            int end = Math.min(start + maxRowsPerChunk, dataLines.size());
            String chunkContent = buildChunk(header, separator, dataLines.subList(start, end));
            int headerRow0 = parseRowStart(segmentMetadata);

            Map<String, Object> chunkMeta = new LinkedHashMap<>(segmentMetadata != null ? segmentMetadata : Map.of());
            chunkMeta.put("chunkIndex", index);
            chunkMeta.put("contentLength", chunkContent.length());
            chunkMeta.put("rowStart", headerRow0 + start + 2);
            chunkMeta.put("rowEnd", headerRow0 + end + 1);
            chunkMeta.put("chunkStrategy", "table_row_window");

            drafts.add(new KnowledgeChunkDraft(chunkContent, writeMetadata(chunkMeta)));
            index++;

            if (end >= dataLines.size()) {
                break;
            }
            start = Math.max(start + 1, end - overlapRows);
        }

        return drafts;
    }

    private String buildChunk(String header, String separator, List<String> dataLines) {
        StringBuilder sb = new StringBuilder();
        sb.append(header).append("\n").append(separator);
        for (String line : dataLines) {
            sb.append("\n").append(line);
        }
        return sb.toString();
    }

    private int parseRowStart(Map<String, Object> metadata) {
        if (metadata == null) return 0;
        Object val = metadata.get("rowStart");
        if (val instanceof Number n) return n.intValue() - 1;
        return 0;
    }

    private KnowledgeChunkDraft buildDraft(String content, int chunkIndex, Map<String, Object> baseMetadata) {
        Map<String, Object> meta = new LinkedHashMap<>(baseMetadata != null ? baseMetadata : Map.of());
        meta.put("chunkIndex", chunkIndex);
        meta.put("contentLength", content.length());
        meta.put("chunkStrategy", "table_row_window");
        return new KnowledgeChunkDraft(content, writeMetadata(meta));
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize table chunk metadata", e);
        }
    }
}
