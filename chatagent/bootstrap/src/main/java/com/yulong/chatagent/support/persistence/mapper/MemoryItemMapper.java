package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.MemoryItemDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper for long-term memory items.
 */
@Mapper
public interface MemoryItemMapper {

    MemoryItemDTO selectById(@Param("id") String id);

    List<MemoryItemDTO> selectByUserIdAndStatus(@Param("userId") String userId,
                                                 @Param("status") String status);

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

    int updateStatus(@Param("id") String id,
                     @Param("status") String status);
}
