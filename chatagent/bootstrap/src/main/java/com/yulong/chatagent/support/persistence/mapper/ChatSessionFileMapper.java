package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.ChatSessionFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper for the {@code chat_session_file} relation table.
 */
@Mapper
public interface ChatSessionFileMapper {
    int insert(ChatSessionFile chatSessionFile);

    List<ChatSessionFile> selectBySessionId(String sessionId);

    ChatSessionFile selectById(String id);

    int updateById(ChatSessionFile chatSessionFile);

    int deleteById(@Param("id") String id);
}
