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
 * @param knowledgeBaseSummary summarized accessible knowledge bases
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
        String knowledgeBaseSummary
) {
}
