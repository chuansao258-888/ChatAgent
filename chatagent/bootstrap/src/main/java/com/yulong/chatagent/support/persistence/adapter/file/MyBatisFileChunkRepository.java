package com.yulong.chatagent.support.persistence.adapter.file;

import com.yulong.chatagent.file.port.FileChunkRepository;
import com.yulong.chatagent.support.dto.FileChunkDTO;
import com.yulong.chatagent.support.persistence.entity.FileChunk;
import com.yulong.chatagent.support.persistence.mapper.FileChunkMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis implementation of the parsed file-chunk repository port.
 */
@Repository
public class MyBatisFileChunkRepository implements FileChunkRepository {

    private final FileChunkMapper fileChunkMapper;

    public MyBatisFileChunkRepository(FileChunkMapper fileChunkMapper) {
        this.fileChunkMapper = fileChunkMapper;
    }

    @Override
    public List<FileChunkDTO> findBySessionFileId(String sessionFileId) {
        List<FileChunkDTO> result = new ArrayList<>();
        for (FileChunk entity : fileChunkMapper.selectBySessionFileId(sessionFileId)) {
            result.add(toDTO(entity));
        }
        return result;
    }

    @Override
    public boolean saveAll(List<FileChunkDTO> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return true;
        }
        List<FileChunk> entities = new ArrayList<>();
        for (FileChunkDTO chunk : chunks) {
            entities.add(toEntity(chunk));
        }
        return fileChunkMapper.insertBatch(entities) > 0;
    }

    @Override
    public boolean deleteBySessionFileId(String sessionFileId) {
        fileChunkMapper.deleteBySessionFileId(sessionFileId);
        return true;
    }

    private FileChunkDTO toDTO(FileChunk entity) {
        return FileChunkDTO.builder()
                .id(entity.getId())
                .sessionFileId(entity.getSessionFileId())
                .chunkIndex(entity.getChunkIndex())
                .content(entity.getContent())
                .tokenCount(entity.getTokenCount())
                .metadata(entity.getMetadata())
                .enabled(entity.getEnabled())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private FileChunk toEntity(FileChunkDTO dto) {
        return FileChunk.builder()
                .id(dto.getId())
                .sessionFileId(dto.getSessionFileId())
                .chunkIndex(dto.getChunkIndex())
                .content(dto.getContent())
                .tokenCount(dto.getTokenCount())
                .metadata(dto.getMetadata())
                .enabled(dto.getEnabled())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }
}
