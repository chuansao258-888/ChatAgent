package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.runtime.AgentDefinition;
import com.yulong.chatagent.agent.runtime.AgentDefinitionLoader;
import com.yulong.chatagent.agent.runtime.AgentKnowledgeBaseSummaryResolver;
import com.yulong.chatagent.agent.runtime.AgentMemoryLoader;
import com.yulong.chatagent.agent.runtime.AgentToolCallbackFactory;
import com.yulong.chatagent.support.dto.AgentDTO;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultAgentRuntimeContextLoader implements AgentRuntimeContextLoader {

    private final AgentDefinitionLoader agentDefinitionLoader;
    private final AgentMemoryLoader agentMemoryLoader;
    private final AgentKnowledgeBaseSummaryResolver knowledgeBaseSummaryResolver;
    private final AgentToolCallbackFactory agentToolCallbackFactory;

    public DefaultAgentRuntimeContextLoader(AgentDefinitionLoader agentDefinitionLoader,
                                            AgentMemoryLoader agentMemoryLoader,
                                            AgentKnowledgeBaseSummaryResolver knowledgeBaseSummaryResolver,
                                            AgentToolCallbackFactory agentToolCallbackFactory) {
        this.agentDefinitionLoader = agentDefinitionLoader;
        this.agentMemoryLoader = agentMemoryLoader;
        this.knowledgeBaseSummaryResolver = knowledgeBaseSummaryResolver;
        this.agentToolCallbackFactory = agentToolCallbackFactory;
    }

    @Override
    public AgentRuntimeContext load(String agentId, String chatSessionId) {
        AgentDefinition definition = agentDefinitionLoader.load(agentId);
        AgentDTO agentConfig = definition.config();
        List<Message> memory = agentMemoryLoader.load(chatSessionId, agentConfig);
        String knowledgeBaseSummary = knowledgeBaseSummaryResolver.resolve(agentConfig);
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
                knowledgeBaseSummary
        );
    }
}
