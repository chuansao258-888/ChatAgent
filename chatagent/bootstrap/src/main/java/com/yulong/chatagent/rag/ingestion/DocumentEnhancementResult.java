package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.rag.parser.ParseSegment;

import java.util.List;
import java.util.Map;

/**
 * Transient document-level enhancement payload produced by {@link DocumentEnhancer} and unpacked
 * immediately into the ingestion context.
 */
public record DocumentEnhancementResult(
        List<ParseSegment> enhancedSegments,
        List<String> keywords,
        List<String> questions,
        Map<String, Object> metadata,
        String cacheKey
) {

    public static DocumentEnhancementResult empty() {
        return new DocumentEnhancementResult(null, List.of(), List.of(), Map.of(), null);
    }
}
