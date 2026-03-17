package com.yulong.chatagent.agent;

import org.springframework.ai.chat.messages.Message;

public interface AgentMessageBridge {
    void persistAndPublish(String chatSessionId, Message message);
}
