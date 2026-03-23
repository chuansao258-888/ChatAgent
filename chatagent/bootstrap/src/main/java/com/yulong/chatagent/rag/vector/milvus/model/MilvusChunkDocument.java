package com.yulong.chatagent.rag.vector.milvus.model;

/**
 * Row payload written to the session-scoped Milvus collection.
 *
 * @param retrievalText text used for dense embedding generation
 * @param bm25Text text mirrored into the BM25 sparse function input field
 */
public record MilvusChunkDocument(
        String chunkId,
        String sessionId,
        String sessionFileId,
        int chunkIndex,
        String fileName,
        String content,
        String contextText,
        String retrievalText,
        String bm25Text,
        boolean enabled,
        long createdAtEpochMillis,
        float[] embedding
) {
}
