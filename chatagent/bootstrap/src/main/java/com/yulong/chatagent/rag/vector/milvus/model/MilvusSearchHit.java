package com.yulong.chatagent.rag.vector.milvus.model;

import lombok.Builder;
import lombok.With;

import java.util.List;

/**
 * One Milvus similarity hit returned from a vector-store collection.
 *
 * @param score dense similarity score or BM25 sparse score, depending on the retrieval path
 */
@Builder
@With
public record MilvusSearchHit(
        String chunkId,
        String sourceId,
        String documentId,
        Integer chunkIndex,
        String documentName,
        String sectionPath,
        String content,
        String contextText,
        String retrievalText,
        List<String> documentKeywords,
        List<String> documentQuestions,
        Double score,
        String scoreType
) {
    public MilvusSearchHit {
        if (scoreType == null || scoreType.isBlank()) {
            scoreType = score == null ? "fallback" : "retrieval";
        }
        documentKeywords = documentKeywords == null ? List.of() : List.copyOf(documentKeywords);
        documentQuestions = documentQuestions == null ? List.of() : List.copyOf(documentQuestions);
    }

    public MilvusSearchHit(
            String chunkId,
            String sourceId,
            String documentId,
            Integer chunkIndex,
            String documentName,
            String sectionPath,
            String content,
            String contextText,
            String retrievalText,
            Double score
    ) {
        this(chunkId, sourceId, documentId, chunkIndex, documentName, sectionPath, content, contextText, retrievalText, List.of(), List.of(), score, null);
    }

    public MilvusSearchHit(
            String chunkId,
            String sourceId,
            String documentId,
            Integer chunkIndex,
            String documentName,
            String sectionPath,
            String content,
            String contextText,
            String retrievalText,
            List<String> documentKeywords,
            List<String> documentQuestions,
            Double score
    ) {
        this(chunkId, sourceId, documentId, chunkIndex, documentName, sectionPath, content, contextText, retrievalText, documentKeywords, documentQuestions, score, null);
    }
}
