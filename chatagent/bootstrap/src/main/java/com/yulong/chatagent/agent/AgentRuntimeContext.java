package com.yulong.chatagent.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Immutable runtime payload required to instantiate a {@link ChatAgent}.
 *
 * @param agentId agent identifier
 * @param name agent display name
 * @param description agent description
 * @param systemPrompt resolved system prompt
 * @param model target model name
 * @param maxMessages memory window size
 * @param memory restored chat memory
 * @param toolCallbacks runtime tool callbacks
 * @param sessionFileSummary summarized attached session files
 * @param sessionSummary historical context summary (L2 memory)
 * @param userProfileSummary persistent user profile summary (L3 memory)
 */
public record AgentRuntimeContext(
        String agentId,
        String name,
        String description,
        String systemPrompt,
        String model,
        Integer maxMessages,
        List<Message> memory,
        List<ToolCallback> toolCallbacks,
        String sessionFileSummary,
        String sessionSummary,
        String userProfileSummary
) {
}
