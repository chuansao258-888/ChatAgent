package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.knowledge.model.response.GetIngestionTasksResponse;
import com.yulong.chatagent.knowledge.model.vo.IngestionTaskVO;
import com.yulong.chatagent.support.dto.IngestionTaskDTO;

/**
 * Coordinates asynchronous document-ingestion tasks.
 */
public interface IngestionTaskService {

    /**
     * Creates a pending ingestion task before background processing starts.
     *
     * @param kbId knowledge base identifier
     * @param documentId document identifier
     * @param filePath stored file path
     * @param fileType uploaded file type
     * @return persisted task DTO
     */
    IngestionTaskDTO createPendingTask(String kbId, String documentId, String filePath, String fileType);

    /**
     * Starts asynchronous markdown ingestion for a pending task.
     *
     * @param taskId task identifier
     * @param kbId knowledge base identifier
     * @param documentId document identifier
     * @param filePath stored file path
     */
    void runMarkdownTaskAsync(String taskId, String kbId, String documentId, String filePath);

    /**
     * Returns the latest state of one ingestion task.
     *
     * @param taskId task identifier
     * @return task view object
     */
    IngestionTaskVO getByTaskId(String taskId);

    /**
     * Lists ingestion tasks belonging to one knowledge base.
     *
     * @param kbId knowledge base identifier
     * @return task list response
     */
    GetIngestionTasksResponse listByKnowledgeBaseId(String kbId);
}
