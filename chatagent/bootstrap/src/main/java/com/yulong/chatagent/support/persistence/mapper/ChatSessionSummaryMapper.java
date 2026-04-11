package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis mapper for chat session summaries.
 */
@Mapper
public interface ChatSessionSummaryMapper {

    ChatSessionSummaryDTO selectBySessionId(String sessionId);

    int insert(ChatSessionSummaryDTO summary);

    int updateBySessionIdAndVersion(ChatSessionSummaryDTO summary);

    int deleteBySessionId(String sessionId);
}
