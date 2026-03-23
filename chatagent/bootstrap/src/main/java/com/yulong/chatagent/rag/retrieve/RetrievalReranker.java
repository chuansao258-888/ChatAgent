package com.yulong.chatagent.rag.retrieve;

import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;

import java.util.List;

/**
 * Reorders fused retrieval candidates before final top-k truncation.
 */
public interface RetrievalReranker {

    /**
     * Reorders the provided candidates and returns them in best-first order.
     */
    List<MilvusSearchHit> rerank(String queryText, List<MilvusSearchHit> candidates);
}
