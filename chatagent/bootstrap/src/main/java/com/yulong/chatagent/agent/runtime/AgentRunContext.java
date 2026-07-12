package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.AgentMessageBridge;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;
import com.yulong.chatagent.agent.tools.ToolApprovalPort;
import com.yulong.chatagent.agent.tools.ToolExecutionLedgerPort;
import com.yulong.chatagent.chat.routing.LLMService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Agent 运行时上下文——传递给 AgentRuntimeEngine 的不可变快照。
 * <p>
 * 包含一次 Agent run 所需的全部依赖：记忆、工具、模型服务、消息桥接器、策略、执行契约等。
 * 引擎通过此上下文执行 ReAct 或 DeepThink 循环，不依赖外部 mutable 状态。
 * <p>
 * ARRB Phase 1（cross-review F-1/F-2/F-4/F-5）：可选的 approvalPort/ledgerPort 让运行时
 * 在装配 coordinator 时启用确认门、journal CAS、预算与重试。null 表示该 run 不启用
 * 对应的运行时级安全行为（向后兼容既有调用方）。
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
        String relevantLongTermMemories,
        AgentMessageBridge messageBridge,
        AgentRunPolicy policy,
        AgentExecutionMode executionMode,
        TurnExecutionContract executionContract,
        ToolApprovalPort approvalPort,
        ToolExecutionLedgerPort ledgerPort
) {
    /** 向后兼容的 16 参构造：approval/ledger 默认 null。 */
    public AgentRunContext(
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
            String relevantLongTermMemories,
            AgentMessageBridge messageBridge,
            AgentRunPolicy policy,
            AgentExecutionMode executionMode,
            TurnExecutionContract executionContract) {
        this(agentId, name, chatSessionId, turnId, systemPrompt, promptLoader, llmService,
                chatMemory, chatOptions, availableTools, sessionFileSummary, relevantLongTermMemories,
                messageBridge, policy, executionMode, executionContract, null, null);
    }
}
