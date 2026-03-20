package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.ChatSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @author charon
 * @description 针对表【chat_session】的数据库操作Mapper
 * @createDate 2025-12-02 14:52:46
 * @Entity com.yulong.chatagent.support.persistence.entity.ChatSession
 */
@Mapper
public interface ChatSessionMapper {
    int insert(ChatSession chatSession);

    ChatSession selectById(String id);

    List<ChatSession> selectByUserId(String userId);

    List<ChatSession> selectByAgentIdAndUserId(@Param("agentId") String agentId, @Param("userId") String userId);

    int deleteById(String id);

    int updateById(ChatSession chatSession);
}
