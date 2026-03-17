package com.yulong.chatagent.support.persistence.adapter.knowledge;

import com.yulong.chatagent.knowledge.port.IngestionTaskRepository;
import com.yulong.chatagent.support.dto.IngestionTaskDTO;
import com.yulong.chatagent.support.persistence.entity.IngestionTask;
import com.yulong.chatagent.support.persistence.converter.IngestionTaskConverter;
import com.yulong.chatagent.support.persistence.mapper.IngestionTaskMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class MyBatisIngestionTaskRepository implements IngestionTaskRepository {
    private final IngestionTaskMapper ingestionTaskMapper;
    private final IngestionTaskConverter ingestionTaskConverter;

    public MyBatisIngestionTaskRepository(IngestionTaskMapper ingestionTaskMapper,
                                          IngestionTaskConverter ingestionTaskConverter) {
        this.ingestionTaskMapper = ingestionTaskMapper;
        this.ingestionTaskConverter = ingestionTaskConverter;
    }

    @Override
    public IngestionTaskDTO findById(String id) {
        IngestionTask entity = ingestionTaskMapper.selectById(id);
        return entity == null ? null : ingestionTaskConverter.toDTO(entity);
    }

    @Override
    public List<IngestionTaskDTO> findByKnowledgeBaseId(String kbId) {
        return ingestionTaskMapper.selectByKbId(kbId).stream()
                .map(ingestionTaskConverter::toDTO)
                .toList();
    }

    @Override
    public boolean save(IngestionTaskDTO task) {
        IngestionTask entity = ingestionTaskConverter.toEntity(task);
        boolean success = ingestionTaskMapper.insert(entity) > 0;
        if (success) {
            task.setId(entity.getId());
        }
        return success;
    }

    @Override
    public boolean deleteByDocumentId(String documentId) {
        return ingestionTaskMapper.deleteByDocumentId(documentId) > 0;
    }

    @Override
    public boolean markRunning(String id, LocalDateTime startedAt) {
        return ingestionTaskMapper.updateStatusToRunning(id, startedAt) > 0;
    }

    @Override
    public boolean markSuccess(String id, Integer chunkCount, LocalDateTime finishedAt) {
        return ingestionTaskMapper.updateStatusToSuccess(id, chunkCount, finishedAt) > 0;
    }

    @Override
    public boolean markFailed(String id, String errorMessage, LocalDateTime finishedAt) {
        return ingestionTaskMapper.updateStatusToFailed(id, errorMessage, finishedAt) > 0;
    }

    @Override
    public boolean existsActiveTaskByDocumentId(String documentId) {
        return ingestionTaskMapper.selectActiveByDocId(documentId);
    }
}
