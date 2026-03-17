package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.agent.ChatAgent;
import com.yulong.chatagent.agent.ChatAgentFactory;
import lombok.AllArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class ChatEventListener {

    private final ChatAgentFactory chatAgentFactory;

    @Async
    @EventListener
    public void handle(ChatEvent event) {
        ChatAgent chatAgent = chatAgentFactory.create(event.getAgentId(), event.getSessionId());
        chatAgent.run();
    }
}
