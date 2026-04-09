package com.yulong.chatagent.agent;

import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.intent.application.IntentResolution;
import org.springframework.stereotype.Component;

/**
 * Factory that materializes fully configured {@link ChatAgent} instances.
 */
@Component
public class ChatAgentFactory {

    private final LLMService llmService;
    private final AgentRuntimeContextLoader agentRuntimeContextLoader;
    private final AgentMessageBridge agentMessageBridge;

    public ChatAgentFactory(LLMService llmService,
                            AgentRuntimeContextLoader agentRuntimeContextLoader,
                            AgentMessageBridge agentMessageBridge) {
        this.llmService = llmService;
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

        return new ChatAgent(
                context.agentId(),
                context.name(),
                context.description(),
                context.systemPrompt(),
                llmService,
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
