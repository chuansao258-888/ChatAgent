package com.yulong.chatagent.knowledge.converter;

import com.yulong.chatagent.knowledge.model.vo.IngestionTaskVO;
import com.yulong.chatagent.support.dto.IngestionTaskDTO;
import com.yulong.chatagent.support.persistence.entity.IngestionTask;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
public class IngestionTaskConverter {

    public IngestionTask toEntity(IngestionTaskDTO dto) {
        Assert.notNull(dto, "IngestionTaskDTO cannot be null");

        return IngestionTask.builder()
                .id(dto.getId())
                .kbId(dto.getKbId())
                .documentId(dto.getDocumentId())
                .filePath(dto.getFilePath())
                .fileType(dto.getFileType())
                .status(dto.getStatus())
                .chunkCount(dto.getChunkCount())
                .errorMessage(dto.getErrorMessage())
                .startedAt(dto.getStartedAt())
                .finishedAt(dto.getFinishedAt())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }

    public IngestionTaskDTO toDTO(IngestionTask entity) {
        Assert.notNull(entity, "IngestionTask cannot be null");

        return IngestionTaskDTO.builder()
                .id(entity.getId())
                .kbId(entity.getKbId())
                .documentId(entity.getDocumentId())
                .filePath(entity.getFilePath())
                .fileType(entity.getFileType())
                .status(entity.getStatus())
                .chunkCount(entity.getChunkCount())
                .errorMessage(entity.getErrorMessage())
                .startedAt(entity.getStartedAt())
                .finishedAt(entity.getFinishedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public IngestionTaskVO toVO(IngestionTaskDTO dto) {
        Assert.notNull(dto, "IngestionTaskDTO cannot be null");

        return IngestionTaskVO.builder()
                .id(dto.getId())
                .kbId(dto.getKbId())
                .documentId(dto.getDocumentId())
                .fileType(dto.getFileType())
                .status(dto.getStatus())
                .chunkCount(dto.getChunkCount())
                .errorMessage(dto.getErrorMessage())
                .startedAt(dto.getStartedAt())
                .finishedAt(dto.getFinishedAt())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }

    public IngestionTaskVO toVO(IngestionTask entity) {
        return toVO(toDTO(entity));
    }

}
