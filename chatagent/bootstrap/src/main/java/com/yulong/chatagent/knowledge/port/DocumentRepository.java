package com.yulong.chatagent.knowledge.port;

import com.yulong.chatagent.support.dto.DocumentDTO;

import java.util.List;

public interface DocumentRepository {

    List<DocumentDTO> findAll();

    DocumentDTO findById(String id);

    List<DocumentDTO> findByKnowledgeBaseId(String kbId);

    boolean save(DocumentDTO document);

    boolean update(DocumentDTO document);

    boolean deleteById(String id);
}
