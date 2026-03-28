package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.ChatSessionSummary;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis mapper for chat session summaries.
 */
@Mapper
public interface ChatSessionSummaryMapper {

    ChatSessionSummary selectBySessionId(String sessionId);

    int insert(ChatSessionSummary summary);

    int updateBySessionIdAndVersion(ChatSessionSummary summary);

    int deleteBySessionId(String sessionId);
}
