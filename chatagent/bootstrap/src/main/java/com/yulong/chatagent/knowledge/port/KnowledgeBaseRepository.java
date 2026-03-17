package com.yulong.chatagent.knowledge.port;

import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;

import java.util.List;

public interface KnowledgeBaseRepository {

    List<KnowledgeBaseDTO> findAll();

    KnowledgeBaseDTO findById(String id);

    List<KnowledgeBaseDTO> findByIds(List<String> ids);

    boolean save(KnowledgeBaseDTO knowledgeBase);

    boolean update(KnowledgeBaseDTO knowledgeBase);

    boolean deleteById(String id);
}
