package com.yulong.chatagent.conversation.port;

import com.yulong.chatagent.support.dto.ChatMessageDTO;

import java.util.List;

public interface ChatMessageRepository {

    List<ChatMessageDTO> findBySessionId(String sessionId);

    List<ChatMessageDTO> findRecentBySessionId(String sessionId, int limit);

    ChatMessageDTO findById(String id);

    boolean save(ChatMessageDTO chatMessage);

    boolean update(ChatMessageDTO chatMessage);

    boolean deleteById(String id);
}
