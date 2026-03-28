package com.yulong.chatagent.knowledge.port;

import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;

import java.util.List;

/**
 * Persistence port for knowledge-base metadata.
 */
public interface KnowledgeBaseRepository {

    List<KnowledgeBaseDTO> findAll();

    List<KnowledgeBaseDTO> findByIds(List<String> ids);

    List<String> filterActiveIds(List<String> ids);

    KnowledgeBaseDTO findById(String id);

    boolean save(KnowledgeBaseDTO knowledgeBase);

    boolean update(KnowledgeBaseDTO knowledgeBase);
}
