package com.yulong.chatagent.support.persistence.adapter.file;

import com.yulong.chatagent.file.port.FileChunkRepository;
import com.yulong.chatagent.support.dto.FileChunkDTO;
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
        return new ArrayList<>(fileChunkMapper.selectBySessionFileId(sessionFileId));
    }

    @Override
    public boolean saveAll(List<FileChunkDTO> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return true;
        }
        return fileChunkMapper.insertBatch(chunks) > 0;
    }

    @Override
    public boolean deleteBySessionFileId(String sessionFileId) {
        fileChunkMapper.deleteBySessionFileId(sessionFileId);
        return true;
    }
}
