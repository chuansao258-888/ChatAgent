package com.yulong.chatagent.rag.model;

/**
 * Structured retrieval result shared across different retrieval sources.
 *
 * <p>Phase 1A introduces this contract before knowledge-base retrieval lands,
 * so session-file search can stop leaking prompt-shaped strings into the rest
 * of the application.</p>
 */
public record RetrievalHit(
        RagSourceType sourceType,
        String sourceId,
        String documentId,
        String documentName,
        Integer chunkIndex,
        String sectionPath,
        String content,
        String contextText,
        Double score,
        String scoreType,
        boolean isFallback
) {
}
