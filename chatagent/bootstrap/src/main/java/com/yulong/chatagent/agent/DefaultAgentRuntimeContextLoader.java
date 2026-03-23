package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.runtime.AgentDefinition;
import com.yulong.chatagent.agent.runtime.AgentDefinitionLoader;
import com.yulong.chatagent.agent.runtime.AgentMemoryLoader;
import com.yulong.chatagent.agent.runtime.AgentSessionFileSummaryResolver;
import com.yulong.chatagent.agent.runtime.AgentToolCallbackFactory;
import com.yulong.chatagent.agent.runtime.AgentUserProfileSummaryResolver;
import com.yulong.chatagent.support.dto.AgentDTO;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default runtime-context loader that assembles all data needed to run an agent.
 */
@Component
public class DefaultAgentRuntimeContextLoader implements AgentRuntimeContextLoader {

    private final AgentDefinitionLoader agentDefinitionLoader;
    private final AgentMemoryLoader agentMemoryLoader;
    private final AgentSessionFileSummaryResolver sessionFileSummaryResolver;
    private final AgentUserProfileSummaryResolver userProfileSummaryResolver;
    private final AgentToolCallbackFactory agentToolCallbackFactory;

    public DefaultAgentRuntimeContextLoader(AgentDefinitionLoader agentDefinitionLoader,
                                            AgentMemoryLoader agentMemoryLoader,
                                            AgentSessionFileSummaryResolver sessionFileSummaryResolver,
                                            AgentUserProfileSummaryResolver userProfileSummaryResolver,
                                            AgentToolCallbackFactory agentToolCallbackFactory) {
        this.agentDefinitionLoader = agentDefinitionLoader;
        this.agentMemoryLoader = agentMemoryLoader;
        this.sessionFileSummaryResolver = sessionFileSummaryResolver;
        this.userProfileSummaryResolver = userProfileSummaryResolver;
        this.agentToolCallbackFactory = agentToolCallbackFactory;
    }

    @Override
    public AgentRuntimeContext load(String agentId, String chatSessionId) {
        // Runtime context is composed from persisted configuration, recent memory,
        // attached session-file context, and concrete callback instances for allowed tools.
        AgentDefinition definition = agentDefinitionLoader.load(agentId);
        AgentDTO agentConfig = definition.config();
        List<Message> memory = agentMemoryLoader.load(chatSessionId, agentConfig);
        String sessionFileSummary = sessionFileSummaryResolver.resolve(agentConfig, chatSessionId);
        String userProfileSummary = userProfileSummaryResolver.resolve(chatSessionId);
        List<ToolCallback> toolCallbacks = agentToolCallbackFactory.create(agentConfig);

        return new AgentRuntimeContext(
                agentConfig.getId(),
                agentConfig.getName(),
                agentConfig.getDescription(),
                agentConfig.getSystemPrompt(),
                agentConfig.getModel().getModelName(),
                agentConfig.getChatOptions().getMessageLength(),
                memory,
                toolCallbacks,
                sessionFileSummary,
                userProfileSummary
        );
    }
}
