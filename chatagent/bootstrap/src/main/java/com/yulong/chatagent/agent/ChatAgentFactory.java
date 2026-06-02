package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.runtime.AgentRunPolicyProperties;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.intent.application.IntentResolution;
import org.springframework.stereotype.Component;

/**
 * ChatAgent 工厂：把持久化配置和运行时上下文装配成一次性的 Agent 实例。
 * <p>
 * 工厂本身不保存会话状态；每次 create 都会重新加载 memory、prompt 和工具列表，
 * 生成一个只服务当前 chat turn 的 {@link ChatAgent}。
 */
@Component
public class ChatAgentFactory {

    private final PromptLoader promptLoader;
    private final LLMService llmService;
    private final AgentRuntimeContextLoader agentRuntimeContextLoader;
    private final AgentMessageBridge agentMessageBridge;
    private final AgentRunPolicyProperties policyProperties;

    public ChatAgentFactory(PromptLoader promptLoader,
                            LLMService llmService,
                            AgentRuntimeContextLoader agentRuntimeContextLoader,
                            AgentMessageBridge agentMessageBridge,
                            AgentRunPolicyProperties policyProperties) {
        this.promptLoader = promptLoader;
        this.llmService = llmService;
        this.agentRuntimeContextLoader = agentRuntimeContextLoader;
        this.agentMessageBridge = agentMessageBridge;
        this.policyProperties = policyProperties;
    }

    /**
     * 为指定 Agent 和会话构造运行实例。
     *
     * @param agentId Agent 配置 ID
     * @param chatSessionId 会话 ID
     * @return 已装配完成、可以直接 run 的 ChatAgent
     */
    public ChatAgent create(String agentId, String chatSessionId) {
        return create(agentId, chatSessionId, null, null, null, null, null);
    }

    public ChatAgent create(String agentId,
                            String chatSessionId,
                            String turnId,
                            IntentResolution intentResolution,
                            String rewrittenInput) {
        return create(agentId, chatSessionId, turnId, intentResolution, rewrittenInput, null, null);
    }

    public ChatAgent create(String agentId,
                            String chatSessionId,
                            String turnId,
                            IntentResolution intentResolution,
                            String rewrittenInput,
                            String userId) {
        return create(agentId, chatSessionId, turnId, intentResolution, rewrittenInput, userId, null);
    }

    public ChatAgent create(String agentId,
                            String chatSessionId,
                            String turnId,
                            IntentResolution intentResolution,
                            String rewrittenInput,
                            String userId,
                            AgentExecutionMode executionMode) {
        // RuntimeContextLoader 负责重活：读取 Agent 定义、恢复记忆、解析摘要、筛选工具并拼系统提示词。
        AgentRuntimeContext context = agentRuntimeContextLoader.load(
                agentId,
                chatSessionId,
                intentResolution,
                rewrittenInput,
                executionMode
        );

        // ChatAgent 是纯运行态对象，不交给 Spring 管理；这样可以把每轮执行的 mutable state 隔离开。
        return new ChatAgent(
                context.agentId(),
                context.name(),
                context.description(),
                context.systemPrompt(),
                promptLoader,
                llmService,
                context.maxMessages(),
                context.memory(),
                context.toolCallbacks(),
                context.sessionFileSummary(),
                context.sessionSummary(),
                context.userProfileSummary(),
                userId,
                turnId,
                chatSessionId,
                agentMessageBridge,
                policyProperties,
                context.executionMode()
        );
    }
}
