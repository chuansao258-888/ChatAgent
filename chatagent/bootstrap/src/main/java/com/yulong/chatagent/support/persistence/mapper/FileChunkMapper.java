package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.FileChunkDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper for parsed file chunk records.
 */
@Mapper
public interface FileChunkMapper {
    int insertBatch(@Param("chunks") List<FileChunkDTO> chunks);

    List<FileChunkDTO> selectBySessionFileId(String sessionFileId);

    int deleteBySessionFileId(String sessionFileId);
}
