package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.AgentMessageBridge;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.routing.LLMService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Agent 运行时上下文——传递给 AgentRuntimeEngine 的不可变快照。
 * <p>
 * 包含一次 Agent run 所需的全部依赖：记忆、工具、模型服务、消息桥接器、策略等。
 * 引擎通过此上下文执行 ReAct 或 DeepThink 循环，不依赖外部 mutable 状态。
 */
public record AgentRunContext(
        String agentId,
        String name,
        String chatSessionId,
        String turnId,
        String systemPrompt,
        PromptLoader promptLoader,
        LLMService llmService,
        ChatMemory chatMemory,
        ChatOptions chatOptions,
        List<ToolCallback> availableTools,
        String sessionFileSummary,
        String userProfileSummary,
        AgentMessageBridge messageBridge,
        AgentRunPolicy policy,
        AgentExecutionMode executionMode
) {
}
