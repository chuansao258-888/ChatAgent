package com.yulong.chatagent.conversation.event;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Async domain-event listener that dispatches chat work to the selected agent.
 */
@Slf4j
@Component
@AllArgsConstructor
public class ChatEventListener {

    private final ChatEventProcessor chatEventProcessor;

    /**
     * Creates and runs the target chat agent when a chat event is published.
     *
     * @param event published chat event
     */
    @Async
    @EventListener
    public void handle(ChatEvent event) {
        try {
            chatEventProcessor.process(event);
        } catch (Exception ex) {
            log.error("Failed to process chat event: agentId={}, sessionId={}, userMessageId={}",
                    event.getAgentId(), event.getSessionId(), event.getChatMessageId(), ex);
            chatEventProcessor.publishFailure(event, ex);
        }
    }
}
