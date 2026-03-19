package com.yulong.chatagent.knowledge.port;

import com.yulong.chatagent.support.dto.IngestionTaskDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Persistence port for asynchronous ingestion tasks.
 */
public interface IngestionTaskRepository {

    /**
     * Loads one task by identifier.
     *
     * @param id task identifier
     * @return matching task or {@code null}
     */
    IngestionTaskDTO findById(String id);

    /**
     * Lists tasks for one knowledge base.
     *
     * @param kbId knowledge base identifier
     * @return matching tasks
     */
    List<IngestionTaskDTO> findByKnowledgeBaseId(String kbId);

    /**
     * Persists a new task.
     *
     * @param task task to save
     * @return {@code true} on success
     */
    boolean save(IngestionTaskDTO task);

    /**
     * Deletes tasks that belong to one document.
     *
     * @param documentId document identifier
     * @return {@code true} when at least one row is affected
     */
    boolean deleteByDocumentId(String documentId);

    /**
     * Marks a task as running.
     *
     * @param id task identifier
     * @param startedAt task start time
     * @return {@code true} on success
     */
    boolean markRunning(String id, LocalDateTime startedAt);

    /**
     * Marks a task as successful.
     *
     * @param id task identifier
     * @param chunkCount produced chunk count
     * @param finishedAt finish time
     * @return {@code true} on success
     */
    boolean markSuccess(String id, Integer chunkCount, LocalDateTime finishedAt);

    /**
     * Marks a task as failed.
     *
     * @param id task identifier
     * @param errorMessage error details
     * @param finishedAt finish time
     * @return {@code true} on success
     */
    boolean markFailed(String id, String errorMessage, LocalDateTime finishedAt);

    /**
     * Checks whether a document still has active ingestion work.
     *
     * @param documentId document identifier
     * @return {@code true} when an active task exists
     */
    boolean existsActiveTaskByDocumentId(String documentId);
}
