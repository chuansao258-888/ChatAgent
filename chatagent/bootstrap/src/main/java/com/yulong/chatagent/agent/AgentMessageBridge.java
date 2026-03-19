package com.yulong.chatagent.agent;

import org.springframework.ai.chat.messages.Message;

/**
 * Bridges agent-generated messages into persistence and realtime delivery channels.
 */
public interface AgentMessageBridge {
    /**
     * Persists one runtime message and publishes it to interested clients.
     *
     * @param chatSessionId chat session identifier
     * @param message runtime message to handle
     */
    void persistAndPublish(String chatSessionId, Message message);
}
