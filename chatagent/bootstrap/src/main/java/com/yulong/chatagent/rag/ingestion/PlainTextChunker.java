package com.yulong.chatagent.rag.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed-size plain-text chunker with overlap and simple natural-boundary backtracking.
 */
@Component
@RequiredArgsConstructor
public class PlainTextChunker {

    private final ObjectMapper objectMapper;

    @Value("${chatagent.rag.chunk.plain.target-chars:1200}")
    private int targetChars;

    @Value("${chatagent.rag.chunk.plain.max-chars:1500}")
    private int maxChars;

    @Value("${chatagent.rag.chunk.plain.min-chars:500}")
    private int minChars;

    @Value("${chatagent.rag.chunk.plain.overlap-chars:150}")
    private int overlapChars;

    /**
     * Splits parser output into retrieval-friendly chunks while avoiding hard cuts whenever a
     * nearby paragraph, sentence, or whitespace boundary is available.
     */
    public List<KnowledgeChunkDraft> chunk(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        int start = 0;
        String normalizedText = text.trim();
        List<KnowledgeChunkDraft> chunks = new ArrayList<>();
        int chunkLimit = chunkLimit();
        while (start < normalizedText.length()) {
            int candidateEnd = Math.min(start + chunkLimit, normalizedText.length());
            int end = findChunkEnd(normalizedText, start, candidateEnd);
            if (end <= start) {
                break;
            }
            String chunkText = normalizedText.substring(start, end).trim();
            if (!StringUtils.hasText(chunkText)) {
                start = end;
                continue;
            }
            String metadata = buildMetadata(chunkText, chunks.size(), start, end);
            chunks.add(new KnowledgeChunkDraft(chunkText, metadata));
            if (end == normalizedText.length()) {
                break;
            }
            int nextStart = end - overlapChars;
            if (nextStart <= start) {
                nextStart = end;
            }
            start = nextStart;

        }
        return chunks;
    }

    private int chunkLimit() {
        int desiredLimit = Math.max(1, targetChars);
        if (maxChars > 0) {
            return Math.min(maxChars, desiredLimit);
        }
        return desiredLimit;
    }

    /**
     * Searches backwards from the target end until it finds a stable boundary that still keeps
     * the chunk above the configured minimum size.
     */
    private int findChunkEnd(String text, int start, int candidateEnd) {
        int minEnd = Math.min(start + minChars, text.length());
        if (candidateEnd >= text.length()) {
            return text.length();
        }

        int pos = text.lastIndexOf("\n\n", candidateEnd - 1);
        if (pos >= minEnd) {
            return pos + 2;
        }

        pos = text.lastIndexOf('\n', candidateEnd - 1);
        if (pos >= minEnd) {
            return pos + 1;
        }

        pos = text.lastIndexOf('。', candidateEnd - 1);
        if (pos >= minEnd) {
            return pos + 1;
        }

        pos = text.lastIndexOf('！', candidateEnd - 1);
        if (pos >= minEnd) {
            return pos + 1;
        }

        pos = text.lastIndexOf('？', candidateEnd - 1);
        if (pos >= minEnd) {
            return pos + 1;
        }

        pos = text.lastIndexOf('.', candidateEnd - 1);
        if (pos >= minEnd) {
            return pos + 1;
        }

        pos = text.lastIndexOf('!', candidateEnd - 1);
        if (pos >= minEnd) {
            return pos + 1;
        }

        pos = text.lastIndexOf('?', candidateEnd - 1);
        if (pos >= minEnd) {
            return pos + 1;
        }

        pos = text.lastIndexOf(' ', candidateEnd - 1);
        if (pos >= minEnd) {
            return pos;
        }

        return candidateEnd;
    }

    /**
     * Stores just enough metadata to explain how a plain-text chunk was produced.
     */
    private String buildMetadata(String chunkText, int chunkIndex, int sourceStart, int sourceEnd) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("chunkStrategy", "plain_text");
        map.put("contentLength", chunkText.length());
        map.put("chunkIndex", chunkIndex);
        map.put("sourceStart", sourceStart);
        map.put("sourceEnd", sourceEnd);
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize plain text chunk metadata", e);
        }
    }

}
