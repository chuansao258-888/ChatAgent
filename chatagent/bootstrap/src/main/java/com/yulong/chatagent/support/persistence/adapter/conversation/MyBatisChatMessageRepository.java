package com.yulong.chatagent.support.persistence.adapter.conversation;

import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import com.yulong.chatagent.conversation.converter.ChatMessageConverter;
import com.yulong.chatagent.support.persistence.entity.ChatMessage;
import com.yulong.chatagent.support.persistence.mapper.ChatMessageMapper;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis-backed implementation of the chat message repository port.
 */
@Repository
public class MyBatisChatMessageRepository implements ChatMessageRepository {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatMessageConverter chatMessageConverter;

    public MyBatisChatMessageRepository(ChatMessageMapper chatMessageMapper,
                                        ChatMessageConverter chatMessageConverter) {
        this.chatMessageMapper = chatMessageMapper;
        this.chatMessageConverter = chatMessageConverter;
    }

    @Override
    public List<ChatMessageDTO> findBySessionId(String sessionId) {
        return toDTOList(chatMessageMapper.selectBySessionId(sessionId));
    }

    @Override
    public List<ChatMessageDTO> findRecentBySessionId(String sessionId, int limit) {
        return toDTOList(chatMessageMapper.selectBySessionIdRecently(sessionId, limit));
    }

    @Override
    public List<ChatMessageDTO> findBySessionIdAndSeqRange(String sessionId, long startExclusiveSeqNo, long endInclusiveSeqNo) {
        return toDTOList(chatMessageMapper.selectBySessionIdAndSeqRange(sessionId, startExclusiveSeqNo, endInclusiveSeqNo));
    }

    @Override
    public Long findMaxSeqNoBySessionId(String sessionId) {
        return chatMessageMapper.selectMaxSeqNoBySessionId(sessionId);
    }

    @Override
    public long countTurnsBySessionId(String sessionId) {
        Long count = chatMessageMapper.selectTurnCountBySessionId(sessionId);
        return count == null ? 0L : count;
    }

    @Override
    public ChatMessageDTO findById(String id) {
        return toDTO(chatMessageMapper.selectById(id));
    }

    @Override
    public boolean save(ChatMessageDTO chatMessage) {
        ChatMessage entity = toEntity(chatMessage);
        boolean saved = chatMessageMapper.insert(entity) > 0;
        if (saved) {
            chatMessage.setId(entity.getId());
            ChatMessage persisted = chatMessageMapper.selectById(entity.getId());
            if (persisted != null) {
                chatMessage.setSeqNo(persisted.getSeqNo());
                chatMessage.setTurnId(persisted.getTurnId());
            }
        }
        return saved;
    }

    @Override
    public boolean update(ChatMessageDTO chatMessage) {
        return chatMessageMapper.updateById(toEntity(chatMessage)) > 0;
    }

    @Override
    public boolean deleteById(String id) {
        return chatMessageMapper.deleteById(id) > 0;
    }

    /**
     * Converts persistence entities to DTOs while preserving list ordering.
     */
    private List<ChatMessageDTO> toDTOList(List<ChatMessage> entities) {
        List<ChatMessageDTO> result = new ArrayList<>();
        for (ChatMessage entity : entities) {
            result.add(toDTO(entity));
        }
        return result;
    }

    /**
     * Converts one persistence entity to a DTO and wraps serialization failures.
     */
    private ChatMessageDTO toDTO(ChatMessage entity) {
        if (entity == null) {
            return null;
        }
        try {
            return chatMessageConverter.toDTO(entity);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize chat message", e);
        }
    }

    /**
     * Converts one DTO to a persistence entity and wraps serialization failures.
     */
    private ChatMessage toEntity(ChatMessageDTO dto) {
        try {
            return chatMessageConverter.toEntity(dto);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize chat message", e);
        }
    }
}
