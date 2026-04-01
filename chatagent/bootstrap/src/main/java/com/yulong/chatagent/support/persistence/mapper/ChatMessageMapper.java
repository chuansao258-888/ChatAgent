package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @author charon
 * @description 针对表【chat_message】的数据库操作Mapper
 * @createDate 2025-12-02 15:40:13
 * @Entity com.yulong.chatagent.support.persistence.entity.ChatMessage
 */
@Mapper
public interface ChatMessageMapper {
    int insert(ChatMessage chatMessage);

    ChatMessage selectById(String id);

    List<ChatMessage> selectBySessionId(String sessionId);

    List<ChatMessage> selectBySessionIdRecently(String sessionId, int limit);

    List<ChatMessage> selectBySessionIdAndSeqRange(String sessionId, long startExclusiveSeqNo, long endInclusiveSeqNo);

    Long selectMaxSeqNoBySessionId(String sessionId);

    Long selectTurnCountBySessionId(String sessionId);

    int deleteById(String id);

    int deleteBySessionIdAndTurnIdAndRoles(String sessionId, String turnId, List<String> roles);

    int updateById(ChatMessage chatMessage);
}
