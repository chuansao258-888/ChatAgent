package com.yulong.chatagent.rag.vector.milvus.model;

/**
 * Row payload written to the knowledge-base Milvus collection.
 */
public record KnowledgeBaseMilvusChunkDocument(
        String chunkId,
        String knowledgeBaseId,
        String documentId,
        int chunkIndex,
        String documentName,
        String sectionPath,
        String content,
        String contextText,
        String retrievalText,
        String bm25Text,
        boolean enabled,
        long createdAtEpochMillis,
        float[] embedding
) {
}
