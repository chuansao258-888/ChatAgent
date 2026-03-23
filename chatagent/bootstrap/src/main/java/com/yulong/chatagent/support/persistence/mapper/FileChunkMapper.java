package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.FileChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper for parsed file chunk records.
 */
@Mapper
public interface FileChunkMapper {
    int insertBatch(@Param("chunks") List<FileChunk> chunks);

    List<FileChunk> selectBySessionFileId(String sessionFileId);

    int deleteBySessionFileId(String sessionFileId);
}
