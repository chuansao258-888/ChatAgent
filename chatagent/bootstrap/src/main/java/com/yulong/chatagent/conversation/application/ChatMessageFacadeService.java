package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.conversation.model.request.CreateChatMessageRequest;
import com.yulong.chatagent.conversation.model.request.UpdateChatMessageRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;

import java.util.List;

/**
 * Facade for chat message operations exposed to the web layer.
 * <p>
 * This interface hides lower-level conversation persistence and agent-specific
 * message creation details behind a single application-facing contract.
 */
public interface ChatMessageFacadeService {

    /**
     * Returns the full message history for a chat session.
     * Internal trace messages (metadata.internal=true) are filtered out.
     *
     * @param sessionId chat session identifier
     * @return ordered message list for the session, excluding internal trace messages
     */
    ChatMessageVO[] getChatMessagesBySessionId(String sessionId);

    /**
     * Returns the most recent messages for prompt assembly or context windows.
     *
     * @param sessionId chat session identifier
     * @param limit maximum number of recent messages to return
     * @return recent messages in DTO form
     */
    List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String sessionId, int limit);

    /**
     * Creates a user-originated chat message from a web request payload.
     *
     * @param request create message request
     * @return created message payload
     */
    CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request);

    /**
     * Creates a chat message directly from an internal DTO.
     *
     * @param chatMessageDTO normalized message input
     * @return created message payload
     */
    CreateChatMessageResponse createChatMessage(ChatMessageDTO chatMessageDTO);

    /**
     * Creates an agent-originated message that should be treated as assistant output.
     *
     * @param request create message request
     * @return created message payload
     */
    CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request);

    /**
     * Appends streamed content to an existing message record.
     *
     * @param chatMessageId target message identifier
     * @param appendContent incremental content fragment
     * @return updated message payload
     */
    CreateChatMessageResponse appendChatMessage(String chatMessageId, String appendContent);

    /**
     * Deletes a message permanently.
     *
     * @param chatMessageId target message identifier
     */
    void deleteChatMessage(String chatMessageId);

    /**
     * Deletes all assistant and tool messages for a specific turn in a session.
     * Used for rollbacks and idempotency during MQ retries.
     *
     * @param sessionId chat session identifier
     * @param turnId session-local turn identifier
     */
    void deleteAssistantAndToolMessagesForTurn(String sessionId, String turnId);

    /**
     * Updates mutable fields of a chat message.
     *
     * @param chatMessageId target message identifier
     * @param request update request payload
     */
    void updateChatMessage(String chatMessageId, UpdateChatMessageRequest request);
}
