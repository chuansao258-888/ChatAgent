package com.yulong.chatagent.conversation.port;

import com.yulong.chatagent.support.dto.ChatSessionDTO;

import java.util.List;

/**
 * Persistence port for chat sessions.
 */
public interface ChatSessionRepository {

    /**
     * Lists chat sessions owned by one user.
     *
     * @param userId owner identifier
     * @return owned sessions
     */
    List<ChatSessionDTO> findByUserId(String userId);

    /**
     * Loads one chat session by identifier.
     *
     * @param id session identifier
     * @return matching session or {@code null}
     */
    ChatSessionDTO findById(String id);

    /**
     * Lists sessions attached to one agent and owned by one user.
     *
     * @param agentId agent identifier
     * @param userId owner identifier
     * @return owned agent sessions
     */
    List<ChatSessionDTO> findByAgentIdAndUserId(String agentId, String userId);

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
