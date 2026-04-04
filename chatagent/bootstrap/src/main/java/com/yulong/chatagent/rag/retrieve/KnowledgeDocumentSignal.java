package com.yulong.chatagent.rag.retrieve;

import com.yulong.chatagent.rag.ingestion.DocumentEnhancementResult;

import java.util.List;
import java.util.Map;

/**
 * Normalized document-level rerank signals loaded from persistence or Redis.
 */
public record KnowledgeDocumentSignal(
        String documentId,
        String enhancerCacheKey,
        List<String> keywords,
        List<String> questions,
        Map<String, Object> metadata
) {
    public KnowledgeDocumentSignal {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        questions = questions == null ? List.of() : List.copyOf(questions);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static KnowledgeDocumentSignal fromEnhancement(String documentId, DocumentEnhancementResult enhancement) {
        if (enhancement == null) {
            return empty(documentId);
        }
        return new KnowledgeDocumentSignal(
                documentId,
                enhancement.cacheKey(),
                enhancement.keywords(),
                enhancement.questions(),
                enhancement.metadata()
        );
    }

    public static KnowledgeDocumentSignal empty(String documentId) {
        return new KnowledgeDocumentSignal(documentId, null, List.of(), List.of(), Map.of());
    }
}
