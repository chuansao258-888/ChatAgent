package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.agent.ChatAgent;
import com.yulong.chatagent.agent.ChatAgentFactory;
import lombok.AllArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Async domain-event listener that dispatches chat work to the selected agent.
 */
@Component
@AllArgsConstructor
public class ChatEventListener {

    private final ChatAgentFactory chatAgentFactory;

    /**
     * Creates and runs the target chat agent when a chat event is published.
     *
     * @param event published chat event
     */
    @Async
    @EventListener
    public void handle(ChatEvent event) {
        ChatAgent chatAgent = chatAgentFactory.create(event.getAgentId(), event.getSessionId());
        chatAgent.run();
    }
}
