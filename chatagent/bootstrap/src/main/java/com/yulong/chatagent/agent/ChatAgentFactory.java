package com.yulong.chatagent.agent;

import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.intent.application.IntentResolution;
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
        return create(agentId, chatSessionId, null, null, null);
    }

    public ChatAgent create(String agentId,
                            String chatSessionId,
                            String turnId,
                            IntentResolution intentResolution,
                            String rewrittenInput) {
        AgentRuntimeContext context = agentRuntimeContextLoader.load(agentId, chatSessionId, intentResolution, rewrittenInput);
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
                context.sessionSummary(),
                context.userProfileSummary(),
                turnId,
                chatSessionId,
                agentMessageBridge
        );
    }
}
