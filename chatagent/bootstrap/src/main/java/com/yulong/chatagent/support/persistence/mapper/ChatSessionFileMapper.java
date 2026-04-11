package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper for the {@code chat_session_file} relation table.
 */
@Mapper
public interface ChatSessionFileMapper {
    int insert(ChatSessionFileDTO chatSessionFile);

    List<ChatSessionFileDTO> selectBySessionId(String sessionId);

    ChatSessionFileDTO selectById(String id);

    int updateById(ChatSessionFileDTO chatSessionFile);

    int deleteById(@Param("id") String id);
}
