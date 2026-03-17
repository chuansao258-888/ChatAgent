package com.yulong.chatagent.rag.model;

import java.time.LocalDateTime;

public record KnowledgeChunk(
        String kbId,
        String documentId,
        String content,
        String metadata,
        float[] embedding,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
