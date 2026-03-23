package com.yulong.chatagent.rag.vector.milvus.model;

/**
 * One Milvus similarity hit returned from the session-scoped chunk collection.
 *
 * @param score dense similarity score or BM25 sparse score, depending on the retrieval path
 */
public record MilvusSearchHit(
        String chunkId,
        String sessionFileId,
        int chunkIndex,
        String fileName,
        String content,
        String contextText,
        String retrievalText,
        float score
) {
}
