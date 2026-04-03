package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.MqOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MyBatis mapper for the {@code t_mq_outbox} table.
 */
@Mapper
public interface MqOutboxMapper {

    int insert(MqOutbox outbox);

    MqOutbox findById(@Param("id") String id);

    List<MqOutbox> selectClaimableBatch(@Param("limit") int limit,
                                        @Param("now") LocalDateTime now,
                                        @Param("staleClaimBefore") LocalDateTime staleClaimBefore,
                                        @Param("maxAttempts") int maxAttempts);

    int markClaimed(@Param("id") String id,
                    @Param("claimedBy") String claimedBy,
                    @Param("claimedAt") LocalDateTime claimedAt,
                    @Param("expectedVersion") int expectedVersion);

    int markSent(@Param("id") String id,
                 @Param("expectedVersion") int expectedVersion);

    int markDiscarded(@Param("id") String id,
                      @Param("lastError") String lastError,
                      @Param("expectedVersion") int expectedVersion);

    int markFailed(@Param("id") String id,
                   @Param("lastError") String lastError,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt,
                   @Param("newRetryCount") int newRetryCount,
                   @Param("expectedVersion") int expectedVersion);

    int markPermanentlyFailed(@Param("id") String id,
                              @Param("lastError") String lastError,
                              @Param("newRetryCount") int newRetryCount,
                              @Param("expectedVersion") int expectedVersion);

    int deleteOlderSentRows(@Param("cutoff") LocalDateTime cutoff);

    int countByStatus(@Param("status") String status);

    List<MqOutbox> findRecent(@Param("eventId") String eventId,
                              @Param("idempotencyKey") String idempotencyKey,
                              @Param("status") String status,
                              @Param("limit") int limit);
}
