package com.yulong.chatagent.rag.repository;

import java.util.List;

public interface KnowledgeChunkSearchRepository {
    List<String> similaritySearch(String kbId, String vectorLiteral, int limit);
}
