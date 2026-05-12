package com.yulong.chatagent.support.persistence.adapter.conversation;

import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.conversation.converter.ChatSessionConverter;
import com.yulong.chatagent.support.persistence.entity.ChatSession;
import com.yulong.chatagent.support.persistence.mapper.ChatSessionMapper;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis-backed implementation of the chat session repository port.
 */
@Repository
public class MyBatisChatSessionRepository implements ChatSessionRepository {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatSessionConverter chatSessionConverter;

    public MyBatisChatSessionRepository(ChatSessionMapper chatSessionMapper,
                                        ChatSessionConverter chatSessionConverter) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatSessionConverter = chatSessionConverter;
    }

    @Override
    public List<ChatSessionDTO> findByUserId(String userId) {
        return toDTOList(chatSessionMapper.selectByUserId(userId));
    }

    @Override
    public ChatSessionDTO findById(String id) {
        return toDTO(chatSessionMapper.selectById(id));
    }

    @Override
    public List<ChatSessionDTO> findByAgentIdAndUserId(String agentId, String userId) {
        return toDTOList(chatSessionMapper.selectByAgentIdAndUserId(agentId, userId));
    }

    @Override
    public boolean save(ChatSessionDTO chatSession) {
        ChatSession entity = toEntity(chatSession);
        boolean saved = chatSessionMapper.insert(entity) > 0;
        if (saved) {
            chatSession.setId(entity.getId());
        }
        return saved;
    }

    @Override
    public Long allocateNextTurnSeq(String sessionId) {
        return chatSessionMapper.allocateNextTurnSeq(sessionId);
    }

    @Override
    public Long findLastCompletedTurnSeq(String sessionId) {
        return chatSessionMapper.selectLastCompletedTurnSeq(sessionId);
    }

    @Override
    public Long advanceCompletedTurnSeq(String sessionId) {
        return chatSessionMapper.advanceCompletedTurnSeq(sessionId);
    }

    @Override
    public boolean update(ChatSessionDTO chatSession) {
        return chatSessionMapper.updateById(toEntity(chatSession)) > 0;
    }

    @Override
    public boolean deleteById(String id) {
        return chatSessionMapper.deleteById(id) > 0;
    }

    /**
     * Converts persistence entities to DTOs while preserving list ordering.
     */
    private List<ChatSessionDTO> toDTOList(List<ChatSession> entities) {
        List<ChatSessionDTO> result = new ArrayList<>();
        for (ChatSession entity : entities) {
            result.add(toDTO(entity));
        }
        return result;
    }

    /**
     * Converts one persistence entity to a DTO and wraps serialization failures.
     */
    private ChatSessionDTO toDTO(ChatSession entity) {
        if (entity == null) {
            return null;
        }
        try {
            return chatSessionConverter.toDTO(entity);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize chat session", e);
        }
    }

    /**
     * Converts one DTO to a persistence entity and wraps serialization failures.
     */
    private ChatSession toEntity(ChatSessionDTO dto) {
        try {
            return chatSessionConverter.toEntity(dto);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize chat session", e);
        }
    }
}
