package com.yulong.chatagent.rag.model;

/**
 * Structured citation metadata attached to assistant messages and SSE payloads.
 */
public record CitationMetadata(
        RagSourceType sourceType,
        String sourceId,
        String documentId,
        String documentName,
        String sectionPath,
        Integer chunkIndex,
        String snippet,
        Double score,
        String scoreType,
        boolean isFallback
) {
}
