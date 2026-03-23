package com.yulong.chatagent.rag.ingestion.model;


public record KnowledgeChunkDraft(
        String content,
        String metadata,
        String embeddingText
) {
}
