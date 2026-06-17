package com.yulong.chatagent.rag.ingestion.model;


/**
 * In-memory draft of a knowledge chunk produced during ingestion, carrying its text content and
 * serialized metadata before persistence.
 */
public record KnowledgeChunkDraft(
        String content,
        String metadata
) {
}
