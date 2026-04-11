package com.yulong.chatagent.support.persistence.adapter.file;

import com.yulong.chatagent.file.port.ChatSessionFileRepository;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import com.yulong.chatagent.support.persistence.mapper.ChatSessionFileMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis implementation of the chat-session attachment repository port.
 */
@Repository
public class MyBatisChatSessionFileRepository implements ChatSessionFileRepository {

    private final ChatSessionFileMapper chatSessionFileMapper;

    public MyBatisChatSessionFileRepository(ChatSessionFileMapper chatSessionFileMapper) {
        this.chatSessionFileMapper = chatSessionFileMapper;
    }

    @Override
    public List<ChatSessionFileDTO> findBySessionId(String sessionId) {
        return new ArrayList<>(chatSessionFileMapper.selectBySessionId(sessionId));
    }

    @Override
    public ChatSessionFileDTO findById(String sessionFileId) {
        return chatSessionFileMapper.selectById(sessionFileId);
    }

    @Override
    public boolean save(ChatSessionFileDTO chatSessionFile) {
        return chatSessionFileMapper.insert(chatSessionFile) > 0;
    }

    @Override
    public boolean update(ChatSessionFileDTO chatSessionFile) {
        return chatSessionFileMapper.updateById(chatSessionFile) > 0;
    }

    @Override
    public boolean deleteById(String sessionFileId) {
        return chatSessionFileMapper.deleteById(sessionFileId) > 0;
    }
}
