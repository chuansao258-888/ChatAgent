package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.IngestionTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IngestionTaskMapper {
    int insert(IngestionTask task);

    IngestionTask selectById(String id);

    List<IngestionTask> selectByKbId(String kbId);

    int updateStatusToRunning(@Param("id") String id, @Param("startedAt") LocalDateTime startedAt);

    int updateStatusToSuccess(@Param("id") String id,
                              @Param("chunkCount") Integer chunkCount,
                              @Param("finishedAt") LocalDateTime finishedAt);

    int updateStatusToFailed(@Param("id") String id,
                             @Param("errorMessage") String errorMessage,
                             @Param("finishedAt") LocalDateTime finishedAt);

    int deleteByDocumentId(@Param("documentId") String documentId);

    boolean selectActiveByDocId(@Param("documentId") String documentId);
}
