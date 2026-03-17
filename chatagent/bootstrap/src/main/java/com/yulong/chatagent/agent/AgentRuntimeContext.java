package com.yulong.chatagent.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

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
