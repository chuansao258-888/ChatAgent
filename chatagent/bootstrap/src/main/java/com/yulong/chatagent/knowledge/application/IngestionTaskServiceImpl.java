package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.exception.ServiceException;
import com.yulong.chatagent.knowledge.model.IngestionTaskStatus;
import com.yulong.chatagent.knowledge.model.response.GetIngestionTasksResponse;
import com.yulong.chatagent.knowledge.model.vo.IngestionTaskEventVO;
import com.yulong.chatagent.knowledge.model.vo.IngestionTaskVO;
import com.yulong.chatagent.knowledge.port.IngestionTaskRepository;
import com.yulong.chatagent.rag.service.DocumentIngestionService;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.support.dto.IngestionTaskDTO;
import com.yulong.chatagent.support.persistence.converter.IngestionTaskConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class IngestionTaskServiceImpl implements IngestionTaskService {
    private final IngestionTaskRepository ingestionTaskRepository;
    private final DocumentIngestionService documentIngestionService;
    private final IngestionTaskConverter ingestionTaskConverter;
    private final SseService sseService;


    public IngestionTaskServiceImpl(IngestionTaskRepository ingestionTaskRepository, DocumentIngestionService documentIngestionService, IngestionTaskConverter ingestionTaskConverter, SseService sseService) {
        this.ingestionTaskRepository = ingestionTaskRepository;
        this.documentIngestionService = documentIngestionService;
        this.ingestionTaskConverter = ingestionTaskConverter;
        this.sseService = sseService;
    }

    @Override
    public IngestionTaskDTO createPendingTask(String kbId, String documentId, String filePath, String fileType) {
        IngestionTaskDTO dto = IngestionTaskDTO.builder()
                .kbId(kbId)
                .documentId(documentId)
                .filePath(filePath)
                .fileType(fileType)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .status(IngestionTaskStatus.PENDING.name())
                .build();
        boolean save = ingestionTaskRepository.save(dto);
        if (!save) {
            throw new ServiceException("Failed to save ingestion task");
        }
        return dto;
    }

    @Async("taskExecutor")
    @Override
    public void runMarkdownTaskAsync(String taskId, String kbId, String documentId, String filePath) {
        ingestionTaskRepository.markRunning(taskId, LocalDateTime.now());
        IngestionTaskEventVO event;
        try {
            int chunkCount = documentIngestionService.ingestMarkdownDocument(kbId, documentId, filePath);
            LocalDateTime finishedAt = LocalDateTime.now();
            ingestionTaskRepository.markSuccess(taskId, chunkCount, finishedAt);
            event = IngestionTaskEventVO.builder()
                    .taskId(taskId)
                    .documentId(documentId)
                    .status(IngestionTaskStatus.SUCCESS.name())
                    .chunkCount(chunkCount)
                    .errorMessage(null)
                    .build();
            log.info("Ingestion task successful: taskId={}, kbId={}, documentId={}", taskId, kbId, documentId);

        } catch (Exception e) {
            log.error("Ingestion task failed: taskId={}, kbId={}, documentId={}", taskId, kbId, documentId, e);
            String errorMessage = e.getMessage();
            LocalDateTime finishedAt = LocalDateTime.now();
            ingestionTaskRepository.markFailed(taskId, errorMessage, finishedAt);
            event = IngestionTaskEventVO.builder()
                    .taskId(taskId)
                    .documentId(documentId)
                    .status(IngestionTaskStatus.FAILED.name())
                    .chunkCount(null)
                    .errorMessage(errorMessage)
                    .build();
        }
        try {
            sseService.send(taskId, event);
        } catch (Exception e) {
            log.warn("Failed to send ingestion task SSE event: taskId={}", taskId, e);
        }
    }

    @Override
    public IngestionTaskVO getByTaskId(String taskId) {
        IngestionTaskDTO ingestionTaskDTO = ingestionTaskRepository.findById(taskId);
        if (ingestionTaskDTO == null) {
            throw new BizException("Ingestion task not found: " + taskId);
        }
        return ingestionTaskConverter.toVO(ingestionTaskDTO);
    }

    @Override
    public GetIngestionTasksResponse listByKnowledgeBaseId(String kbId) {
        List<IngestionTaskDTO> ingestionTaskDTOS = ingestionTaskRepository.findByKnowledgeBaseId(kbId);
        List<IngestionTaskVO> result = new ArrayList<>();
        for (IngestionTaskDTO ingestionTaskDTO : ingestionTaskDTOS) {
            result.add(ingestionTaskConverter.toVO(ingestionTaskDTO));
        }
        return GetIngestionTasksResponse.builder()
                .ingestionTasks(result.toArray(new IngestionTaskVO[0]))
                .build();
    }


}
