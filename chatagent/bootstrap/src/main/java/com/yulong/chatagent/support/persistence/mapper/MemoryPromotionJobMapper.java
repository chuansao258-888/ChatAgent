package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.MemoryPromotionJobDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface MemoryPromotionJobMapper {
    int insertPendingForSession(@Param("sessionId") String sessionId,
                                @Param("seqStartNo") long seqStartNo,
                                @Param("seqEndNo") long seqEndNo);

    MemoryPromotionJobDTO claimNextDue(@Param("now") LocalDateTime now);

    int markCompleted(@Param("id") String id);

    int markRetry(@Param("id") String id,
                  @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                  @Param("lastError") String lastError);

    int markFailed(@Param("id") String id, @Param("lastError") String lastError);

    int reclaimStale(@Param("processingStartedBefore") LocalDateTime processingStartedBefore);

    long countBacklog();
}
