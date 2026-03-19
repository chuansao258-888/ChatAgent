package com.yulong.chatagent.knowledge.controller;

import com.yulong.chatagent.knowledge.application.IngestionTaskService;
import com.yulong.chatagent.knowledge.model.response.GetIngestionTasksResponse;
import com.yulong.chatagent.knowledge.model.vo.IngestionTaskVO;
import com.yulong.chatagent.model.common.ApiResponse;
import com.yulong.chatagent.sse.SseService;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST controller for ingestion task polling and realtime updates.
 */
@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class IngestionTaskController {
    private final IngestionTaskService ingestionTaskService;
    private final SseService sseService;

    /**
     * Lists ingestion tasks for one knowledge base.
     *
     * @param kbId knowledge base identifier
     * @return ingestion task list response
     */
    @GetMapping("/knowledge-bases/{kbId}/ingestion-tasks")
    public ApiResponse<GetIngestionTasksResponse> getIngestionTasksByKbId(@PathVariable String kbId) {
        return ApiResponse.success(ingestionTaskService.listByKnowledgeBaseId(kbId));
    }

    /**
     * Returns one ingestion task by identifier.
     *
     * @param taskId ingestion task identifier
     * @return ingestion task view
     */
    @GetMapping("/ingestion-tasks/{taskId}")
    public ApiResponse<IngestionTaskVO> getIngestionTaskById(@PathVariable String taskId) {
        return ApiResponse.success(ingestionTaskService.getByTaskId(taskId));
    }

    /**
     * Opens a task-scoped SSE stream for live ingestion progress.
     *
     * @param taskId ingestion task identifier
     * @return SSE emitter bound to the task
     */
    @GetMapping(value = "/ingestion-tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter getEvents(@PathVariable String taskId) {
        return sseService.connect(taskId);
    }
}
