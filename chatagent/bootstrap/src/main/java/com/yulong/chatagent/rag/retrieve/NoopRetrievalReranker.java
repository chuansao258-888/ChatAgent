package com.yulong.chatagent.rag.retrieve;

import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default reranker used when no dedicated rerank provider is enabled.
 */
@Component
public class NoopRetrievalReranker implements RetrievalReranker {

    @Override
    public List<MilvusSearchHit> rerank(String queryText, List<MilvusSearchHit> candidates) {
        return candidates;
    }
}
