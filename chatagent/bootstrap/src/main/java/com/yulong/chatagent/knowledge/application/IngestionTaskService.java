package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.knowledge.model.response.GetIngestionTasksResponse;
import com.yulong.chatagent.knowledge.model.vo.IngestionTaskVO;
import com.yulong.chatagent.support.dto.IngestionTaskDTO;

public interface IngestionTaskService {

    IngestionTaskDTO createPendingTask(String kbId, String documentId, String filePath, String fileType);

    void runMarkdownTaskAsync(String taskId, String kbId, String documentId, String filePath);

    IngestionTaskVO getByTaskId(String taskId);

    GetIngestionTasksResponse listByKnowledgeBaseId(String kbId);
}
