package com.yulong.chatagent.file.port;

import com.yulong.chatagent.support.dto.FileChunkDTO;

import java.util.List;

/**
 * Persistence port for parsed file chunks.
 */
public interface FileChunkRepository {

    List<FileChunkDTO> findBySessionFileId(String sessionFileId);

    boolean saveAll(List<FileChunkDTO> chunks);

    boolean deleteBySessionFileId(String sessionFileId);
}
