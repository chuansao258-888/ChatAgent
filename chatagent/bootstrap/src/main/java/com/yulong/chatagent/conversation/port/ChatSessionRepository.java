package com.yulong.chatagent.conversation.port;

import com.yulong.chatagent.support.dto.ChatSessionDTO;

import java.util.List;

public interface ChatSessionRepository {

    List<ChatSessionDTO> findAll();

    ChatSessionDTO findById(String id);

    List<ChatSessionDTO> findByAgentId(String agentId);

    boolean save(ChatSessionDTO chatSession);

    boolean update(ChatSessionDTO chatSession);

    boolean deleteById(String id);
}
