package com.yulong.chatagent.rag.vector.milvus;

import com.yulong.chatagent.rag.vector.milvus.model.KnowledgeBaseMilvusChunkDocument;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;

import java.util.List;

/**
 * Operations for maintaining and querying the knowledge-base Milvus collection.
 */
public interface KnowledgeBaseMilvusIndexService {

    void ensureCollection();

    void upsertChunks(List<KnowledgeBaseMilvusChunkDocument> chunks);

    void deleteByKnowledgeDocumentId(String knowledgeDocumentId);

    void deleteByKnowledgeBaseId(String knowledgeBaseId);

    List<MilvusSearchHit> searchByKnowledgeBaseIds(List<String> knowledgeBaseIds, float[] queryEmbedding, int topK);

    List<MilvusSearchHit> searchByKnowledgeBaseIdsBm25(List<String> knowledgeBaseIds, String queryText, int topK);
}
