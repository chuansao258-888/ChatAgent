package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.ChatSessionSummarySegmentDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper for {@link ChatSessionSummarySegmentDTO} persistence: inserting segments,
 * fetching active segments by session (optionally ordered), and bulk deletion by session.
 */
@Mapper
public interface ChatSessionSummarySegmentMapper {

    int insert(ChatSessionSummarySegmentDTO segment);

    ChatSessionSummarySegmentDTO selectById(@Param("id") String id);

    List<ChatSessionSummarySegmentDTO> selectActiveBySessionId(@Param("sessionId") String sessionId);

    List<ChatSessionSummarySegmentDTO> selectActiveBySessionIdOrdered(@Param("sessionId") String sessionId);

    int deleteBySessionId(@Param("sessionId") String sessionId);
}
