package com.yulong.chatagent.knowledge.port;

import com.yulong.chatagent.support.dto.DocumentDTO;

import java.util.List;

/**
 * Persistence port for document records.
 */
public interface DocumentRepository {

    /**
     * Lists all documents.
     *
     * @return all documents
     */
    List<DocumentDTO> findAll();

    /**
     * Loads one document by identifier.
     *
     * @param id document identifier
     * @return matching document or {@code null}
     */
    DocumentDTO findById(String id);

    /**
     * Lists documents under one knowledge base.
     *
     * @param kbId knowledge base identifier
     * @return matching documents
     */
    List<DocumentDTO> findByKnowledgeBaseId(String kbId);

    /**
     * Persists a new document.
     *
     * @param document document to save
     * @return {@code true} on success
     */
    boolean save(DocumentDTO document);

    /**
     * Updates an existing document.
     *
     * @param document document to update
     * @return {@code true} on success
     */
    boolean update(DocumentDTO document);

    /**
     * Deletes one document by identifier.
     *
     * @param id document identifier
     * @return {@code true} on success
     */
    boolean deleteById(String id);
}
