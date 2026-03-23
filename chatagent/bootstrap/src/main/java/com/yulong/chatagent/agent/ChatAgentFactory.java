package com.yulong.chatagent.agent;

import com.yulong.chatagent.chat.ChatModelRouter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Factory that materializes fully configured {@link ChatAgent} instances.
 */
@Component
public class ChatAgentFactory {

    private final ChatModelRouter chatModelRouter;
    private final AgentRuntimeContextLoader agentRuntimeContextLoader;
    private final AgentMessageBridge agentMessageBridge;

    public ChatAgentFactory(ChatModelRouter chatModelRouter,
                            AgentRuntimeContextLoader agentRuntimeContextLoader,
                            AgentMessageBridge agentMessageBridge) {
        this.chatModelRouter = chatModelRouter;
        this.agentRuntimeContextLoader = agentRuntimeContextLoader;
        this.agentMessageBridge = agentMessageBridge;
    }

    /**
     * Builds a runtime agent for one agent definition and one chat session.
     *
     * @param agentId agent identifier
     * @param chatSessionId chat session identifier
     * @return ready-to-run chat agent
     */
    public ChatAgent create(String agentId, String chatSessionId) {
        AgentRuntimeContext context = agentRuntimeContextLoader.load(agentId, chatSessionId);
        ChatClient chatClient = chatModelRouter.route(context.model());

        return new ChatAgent(
                context.agentId(),
                context.name(),
                context.description(),
                context.systemPrompt(),
                chatClient,
                context.maxMessages(),
                context.memory(),
                context.toolCallbacks(),
                context.sessionFileSummary(),
                context.userProfileSummary(),
                chatSessionId,
                agentMessageBridge
        );
    }
}
