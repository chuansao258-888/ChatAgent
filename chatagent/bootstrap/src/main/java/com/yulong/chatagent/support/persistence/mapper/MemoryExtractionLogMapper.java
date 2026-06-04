package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.MemoryExtractionLogDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for memory extraction idempotency logs.
 */
@Mapper
public interface MemoryExtractionLogMapper {

    MemoryExtractionLogDTO selectByRange(@Param("sessionId") String sessionId,
                                          @Param("seqStartNo") long seqStartNo,
                                          @Param("seqEndNo") long seqEndNo);

    int insert(MemoryExtractionLogDTO log);

    int updateStatus(@Param("id") String id,
                     @Param("status") String status,
                     @Param("errorMessage") String errorMessage);
}
