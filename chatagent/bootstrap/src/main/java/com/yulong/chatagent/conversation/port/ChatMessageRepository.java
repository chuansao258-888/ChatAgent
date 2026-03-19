package com.yulong.chatagent.conversation.port;

import com.yulong.chatagent.support.dto.ChatMessageDTO;

import java.util.List;

/**
 * Persistence port for chat messages.
 */
public interface ChatMessageRepository {

    /**
     * Lists all messages under one chat session.
     *
     * @param sessionId chat session identifier
     * @return session messages
     */
    List<ChatMessageDTO> findBySessionId(String sessionId);

    /**
     * Returns the most recent messages for one chat session.
     *
     * @param sessionId chat session identifier
     * @param limit maximum number of messages to return
     * @return recent messages
     */
    List<ChatMessageDTO> findRecentBySessionId(String sessionId, int limit);

    /**
     * Loads one message by identifier.
     *
     * @param id message identifier
     * @return matching message or {@code null}
     */
    ChatMessageDTO findById(String id);

    /**
     * Persists a new message.
     *
     * @param chatMessage message to save
     * @return {@code true} on success
     */
    boolean save(ChatMessageDTO chatMessage);

    /**
     * Updates an existing message.
     *
     * @param chatMessage message to update
     * @return {@code true} on success
     */
    boolean update(ChatMessageDTO chatMessage);

    /**
     * Deletes one message by identifier.
     *
     * @param id message identifier
     * @return {@code true} on success
     */
    boolean deleteById(String id);
}
