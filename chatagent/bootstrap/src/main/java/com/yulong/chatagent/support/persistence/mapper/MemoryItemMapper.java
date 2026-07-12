package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.MemoryItemDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDateTime;

/**
 * MyBatis mapper for long-term memory items.
 */
@Mapper
public interface MemoryItemMapper {

    MemoryItemDTO selectById(@Param("id") String id);

    MemoryItemDTO selectOwnedById(@Param("userId") String userId, @Param("id") String id);

    List<MemoryItemDTO> selectByUserIdAndStatus(@Param("userId") String userId,
                                                 @Param("status") String status);

    List<MemoryItemDTO> selectIndexCandidates(@Param("limit") int limit);

    MemoryItemDTO selectByUserAndTypeAndHash(@Param("userId") String userId,
                                              @Param("type") String type,
                                              @Param("contentHash") String contentHash);

    /**
     * Atomic upsert using PostgreSQL ON CONFLICT.
     * On duplicate (user_id, type, content_hash), updates tags, source, and updated_at.
     */
    int upsertOnConflict(MemoryItemDTO item);

    int updateIndexStatus(@Param("id") String id,
                          @Param("indexStatus") String indexStatus);

    int correct(@Param("userId") String userId, @Param("id") String id,
                @Param("expectedUpdatedAt") LocalDateTime expectedUpdatedAt,
                @Param("type") String type, @Param("content") String content,
                @Param("contentHash") String contentHash);

    int reactivate(@Param("userId") String userId, @Param("id") String id);

    int archive(@Param("userId") String userId, @Param("id") String id);
}
