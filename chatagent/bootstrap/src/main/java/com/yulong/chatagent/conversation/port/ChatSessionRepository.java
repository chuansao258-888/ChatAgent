package com.yulong.chatagent.conversation.port;

import com.yulong.chatagent.support.dto.ChatSessionDTO;

import java.util.List;

/**
 * Persistence port for chat sessions.
 */
public interface ChatSessionRepository {

    /**
     * Lists all chat sessions.
     *
     * @return all sessions
     */
    List<ChatSessionDTO> findAll();

    /**
     * Loads one chat session by identifier.
     *
     * @param id session identifier
     * @return matching session or {@code null}
     */
    ChatSessionDTO findById(String id);

    /**
     * Lists sessions attached to one agent.
     *
     * @param agentId agent identifier
     * @return agent sessions
     */
    List<ChatSessionDTO> findByAgentId(String agentId);

    /**
     * Persists a new session.
     *
     * @param chatSession session to save
     * @return {@code true} on success
     */
    boolean save(ChatSessionDTO chatSession);

    /**
     * Updates an existing session.
     *
     * @param chatSession session to update
     * @return {@code true} on success
     */
    boolean update(ChatSessionDTO chatSession);

    /**
     * Deletes one session by identifier.
     *
     * @param id session identifier
     * @return {@code true} on success
     */
    boolean deleteById(String id);
}
