package com.yulong.chatagent.agent;

import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.agent.runtime.AgentDefinition;
import com.yulong.chatagent.agent.runtime.AgentDefinitionLoader;
import com.yulong.chatagent.agent.runtime.AgentMemoryLoader;
import com.yulong.chatagent.agent.runtime.AgentSessionFileSummaryResolver;
import com.yulong.chatagent.agent.runtime.AgentSessionSummaryResolver;
import com.yulong.chatagent.agent.runtime.AgentToolCallbackFactory;
import com.yulong.chatagent.agent.runtime.AgentUserProfileSummaryResolver;
import com.yulong.chatagent.support.dto.AgentDTO;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Default runtime-context loader that assembles all data needed to run an agent.
 */
@Component
public class DefaultAgentRuntimeContextLoader implements AgentRuntimeContextLoader {

    private final AgentDefinitionLoader agentDefinitionLoader;
    private final AgentMemoryLoader agentMemoryLoader;
    private final AgentSessionFileSummaryResolver sessionFileSummaryResolver;
    private final AgentSessionSummaryResolver sessionSummaryResolver;
    private final AgentUserProfileSummaryResolver userProfileSummaryResolver;
    private final AgentToolCallbackFactory agentToolCallbackFactory;

    public DefaultAgentRuntimeContextLoader(AgentDefinitionLoader agentDefinitionLoader,
                                            AgentMemoryLoader agentMemoryLoader,
                                            AgentSessionFileSummaryResolver sessionFileSummaryResolver,
                                            AgentSessionSummaryResolver sessionSummaryResolver,
                                            AgentUserProfileSummaryResolver userProfileSummaryResolver,
                                            AgentToolCallbackFactory agentToolCallbackFactory) {
        this.agentDefinitionLoader = agentDefinitionLoader;
        this.agentMemoryLoader = agentMemoryLoader;
        this.sessionFileSummaryResolver = sessionFileSummaryResolver;
        this.sessionSummaryResolver = sessionSummaryResolver;
        this.userProfileSummaryResolver = userProfileSummaryResolver;
        this.agentToolCallbackFactory = agentToolCallbackFactory;
    }

    @Override
    public AgentRuntimeContext load(String agentId, String chatSessionId) {
        return load(agentId, chatSessionId, null, null);
    }

    @Override
    public AgentRuntimeContext load(String agentId,
                                    String chatSessionId,
                                    IntentResolution intentResolution,
                                    String rewrittenInput) {
        AgentDefinition definition = agentDefinitionLoader.load(agentId);
        AgentDTO agentConfig = definition.config();
        List<Message> memory = agentMemoryLoader.load(chatSessionId, agentConfig);
        String sessionFileSummary = sessionFileSummaryResolver.resolve(agentConfig, chatSessionId);
        String sessionSummary = sessionSummaryResolver.resolve(chatSessionId);
        String userProfileSummary = userProfileSummaryResolver.resolve(chatSessionId);
        List<ToolCallback> toolCallbacks = agentToolCallbackFactory.create(agentConfig, intentResolution);
        
        String resolvedSystemPrompt = buildSystemPrompt(
                agentConfig.getSystemPrompt(), 
                sessionSummary, 
                intentResolution, 
                rewrittenInput,
                sessionFileSummary,
                userProfileSummary
        );

        return new AgentRuntimeContext(
                agentConfig.getId(),
                agentConfig.getName(),
                agentConfig.getDescription(),
                resolvedSystemPrompt,
                agentConfig.getModel().getModelName(),
                agentConfig.getChatOptions().getMessageLength(),
                memory,
                toolCallbacks,
                sessionFileSummary,
                sessionSummary,
                userProfileSummary
        );
    }

    private String buildSystemPrompt(String baseSystemPrompt,
                                     String sessionSummary,
                                     IntentResolution intentResolution,
                                     String rewrittenInput,
                                     String sessionFileSummary,
                                     String userProfileSummary) {
        StringBuilder builder = new StringBuilder();
        
        // 1. Base System Prompt
        if (StringUtils.hasText(baseSystemPrompt)) {
            builder.append(baseSystemPrompt.trim()).append("\n\n");
        }

        // 2. [Historical Context Summary] (L2 Memory)
        if (StringUtils.hasText(sessionSummary) && !sessionSummary.contains("No historical context summary available")) {
            builder.append("[Historical Context Summary]\n")
                    .append(sessionSummary).append("\n\n");
        }

        // 3. [Intent Routing Context]
        if (intentResolution != null) {
            builder.append("[Intent Routing Context]\n")
                    .append("- Intent kind: ").append(intentResolution.kind()).append("\n");
            if (StringUtils.hasText(intentResolution.pathLabel())) {
                builder.append("- Intent path: ").append(intentResolution.pathLabel()).append("\n");
            }
            if (!intentResolution.scopedKbIds().isEmpty()) {
                builder.append("- Scoped knowledge bases: ").append(intentResolution.scopedKbIds()).append("\n");
            }
            if (!intentResolution.allowedTools().isEmpty()) {
                builder.append("- Allowed business tools: ").append(intentResolution.allowedTools()).append("\n");
            }
            if (StringUtils.hasText(rewrittenInput)) {
                builder.append("- Search hint: ").append(rewrittenInput).append("\n");
            }
            builder.append("Respect the turn scope. Do not search or call tools outside the resolved intent boundary.\n\n");
        }

        // 4. [Session Context] (Files & Knowledge Bases & User Profile)
        builder.append("[Session Context]\n");
        if (StringUtils.hasText(sessionFileSummary)) {
            builder.append("- Assets: ").append(sessionFileSummary).append("\n");
        }
        if (StringUtils.hasText(userProfileSummary)) {
            builder.append("- User Profile: ").append(userProfileSummary).append("\n");
        }

        return builder.toString().trim();
    }
}
