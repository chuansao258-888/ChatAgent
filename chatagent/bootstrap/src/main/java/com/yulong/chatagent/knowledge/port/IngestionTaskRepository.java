package com.yulong.chatagent.knowledge.port;

import com.yulong.chatagent.support.dto.IngestionTaskDTO;

import java.time.LocalDateTime;
import java.util.List;

public interface IngestionTaskRepository {

    IngestionTaskDTO findById(String id);

    List<IngestionTaskDTO> findByKnowledgeBaseId(String kbId);

    boolean save(IngestionTaskDTO task);

    boolean deleteByDocumentId(String documentId);

    boolean markRunning(String id, LocalDateTime startedAt);

    boolean markSuccess(String id, Integer chunkCount, LocalDateTime finishedAt);

    boolean markFailed(String id, String errorMessage, LocalDateTime finishedAt);

    boolean existsActiveTaskByDocumentId(String documentId);
}
