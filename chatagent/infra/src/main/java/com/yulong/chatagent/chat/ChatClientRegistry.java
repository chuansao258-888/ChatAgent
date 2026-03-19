package com.yulong.chatagent.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.Map;

@Component
/**
 * Registry of named {@link ChatClient} beans exposed by the infra module.
 */
public class ChatClientRegistry {

    private final Map<String, ChatClient> chatClients;

    public ChatClientRegistry(Map<String, ChatClient> chatClients) {
        this.chatClients = chatClients;
    }

    public ChatClient get(String key) {
        return chatClients.get(key);
    }

    /**
     * Returns a configured chat client or fails fast when the requested model
     * is not available in the Spring context.
     *
     * @param key model/client name
     * @return configured chat client
     */
    public ChatClient getRequired(String key) {
        ChatClient chatClient = chatClients.get(key);
        if (chatClient == null) {
            throw new IllegalStateException("No ChatClient configured for model: " + key);
        }
        return chatClient;
    }

    public boolean supports(String key) {
        return chatClients.containsKey(key);
    }

    public Set<String> availableModels() {
        return chatClients.keySet();
    }
}
