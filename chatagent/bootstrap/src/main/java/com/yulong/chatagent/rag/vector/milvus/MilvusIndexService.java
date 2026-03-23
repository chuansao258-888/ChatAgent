package com.yulong.chatagent.rag.vector.milvus;

import com.yulong.chatagent.rag.vector.milvus.model.MilvusChunkDocument;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;

import java.util.List;

/**
 * Operations for maintaining and querying the Milvus chunk index.
 */
public interface MilvusIndexService {

    /**
     * Creates the configured collection when it does not exist yet.
     */
    void ensureCollection();

    /**
     * Upserts chunk rows into the session-file collection.
     */
    void upsertChunks(List<MilvusChunkDocument> chunks);

    void deleteBySessionFileId(String sessionFileId);

    void deleteBySessionId(String sessionId);

    /**
     * Dense vector search scoped to a list of session-file ids.
     */
    List<MilvusSearchHit> searchBySessionFileIds(List<String> sessionFileIds, float[] queryEmbedding, int topK);

    /**
     * BM25 sparse search scoped to a list of session-file ids.
     */
    List<MilvusSearchHit> searchBySessionFileIdsBm25(List<String> sessionFileIds, String queryText, int topK);

    /**
     * Dense vector search scoped to one chat session.
     */
    List<MilvusSearchHit> searchBySession(String sessionId, float[] queryEmbedding, int topK);

    /**
     * BM25 sparse search scoped to one chat session.
     */
    List<MilvusSearchHit> searchBySessionBm25(String sessionId, String queryText, int topK);
}
