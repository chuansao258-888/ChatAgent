package com.yulong.chatagent.rag.repository;

import java.util.List;

/**
 * Search port for vector similarity retrieval over knowledge chunks.
 */
public interface KnowledgeChunkSearchRepository {
    /**
     * Returns the top similar chunk contents for a vector query.
     *
     * @param kbId knowledge base identifier
     * @param vectorLiteral pgvector literal
     * @param limit result size limit
     * @return matching chunk contents
     */
    List<String> similaritySearch(String kbId, String vectorLiteral, int limit);
}
