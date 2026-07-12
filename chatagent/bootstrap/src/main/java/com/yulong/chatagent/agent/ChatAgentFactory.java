package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.runtime.AgentRunPolicyProperties;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.contract.TurnContractProperties;
import com.yulong.chatagent.agent.runtime.contract.TurnContractEnforcementException;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;
import com.yulong.chatagent.agent.tools.ToolApprovalPort;
import com.yulong.chatagent.agent.tools.ToolExecutionLedgerPort;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.intent.application.IntentResolution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * ChatAgent 工厂：把持久化配置和运行时上下文装配成一次性的 Agent 实例。
 * <p>
 * 工厂本身不保存会话状态；每次 create 都会重新加载 memory、prompt 和工具列表，
 * 生成一个只服务当前 chat turn 的 {@link ChatAgent}。
 * <p>
 * ARRB Phase 1（F-1/F-2）：注入 approval/ledger ports 并透传到每次 run 的
 * {@link com.yulong.chatagent.agent.runtime.AgentRunContext}，使 coordinator 在生产路径上
 * 启用确认门与 journal CAS。
 */
@Component
public class ChatAgentFactory {

    private final PromptLoader promptLoader;
    private final LLMService llmService;
    private final AgentRuntimeContextLoader agentRuntimeContextLoader;
    private final AgentMessageBridge agentMessageBridge;
    private final AgentRunPolicyProperties policyProperties;
    private final TurnContractProperties contractProperties;
    private final ToolApprovalPort approvalPort;
    private final ToolExecutionLedgerPort ledgerPort;

    @Autowired
    public ChatAgentFactory(PromptLoader promptLoader,
                            LLMService llmService,
                            AgentRuntimeContextLoader agentRuntimeContextLoader,
                            AgentMessageBridge agentMessageBridge,
                            AgentRunPolicyProperties policyProperties,
                            TurnContractProperties contractProperties,
                            ToolApprovalPort approvalPort,
                            ToolExecutionLedgerPort ledgerPort) {
        this.promptLoader = promptLoader;
        this.llmService = llmService;
        this.agentRuntimeContextLoader = agentRuntimeContextLoader;
        this.agentMessageBridge = agentMessageBridge;
        this.policyProperties = policyProperties;
        this.contractProperties = contractProperties;
        this.approvalPort = approvalPort;
        this.ledgerPort = ledgerPort;
    }

    ChatAgentFactory(PromptLoader promptLoader,
                     LLMService llmService,
                     AgentRuntimeContextLoader agentRuntimeContextLoader,
                     AgentMessageBridge agentMessageBridge,
                     AgentRunPolicyProperties policyProperties,
                     TurnContractProperties contractProperties) {
        this(promptLoader, llmService, agentRuntimeContextLoader, agentMessageBridge,
                policyProperties, contractProperties, null, null);
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
        return create(agentId, chatSessionId, turnId, intentResolution, rewrittenInput, userId, executionMode, null);
    }

    public ChatAgent create(String agentId,
                            String chatSessionId,
                            String turnId,
                            IntentResolution intentResolution,
                            String rewrittenInput,
                            String userId,
                            AgentExecutionMode executionMode,
                            String currentUserInput) {
        return create(agentId, chatSessionId, turnId, intentResolution, rewrittenInput, userId, executionMode, currentUserInput, null);
    }

    public ChatAgent create(String agentId,
                            String chatSessionId,
                            String turnId,
                            IntentResolution intentResolution,
                            String rewrittenInput,
                            String userId,
                            AgentExecutionMode executionMode,
                            String currentUserInput,
                            TurnExecutionContract executionContract) {
        boolean retrievalEnforced = contractProperties.isRetrievalEnforced();
        if (retrievalEnforced && executionContract == null) {
            throw new TurnContractEnforcementException(
                    "Retrieval enforce mode requires a turn execution contract");
        }
        TurnExecutionContract enforcedContract = retrievalEnforced ? executionContract : null;
        // RuntimeContextLoader 负责重活：读取 Agent 定义、恢复记忆、解析摘要、筛选工具并拼系统提示词。
        AgentRuntimeContext context = enforcedContract == null
                ? agentRuntimeContextLoader.load(
                        agentId, chatSessionId, intentResolution, rewrittenInput,
                        executionMode, currentUserInput)
                : agentRuntimeContextLoader.load(
                        agentId, chatSessionId, intentResolution, rewrittenInput,
                        executionMode, currentUserInput, enforcedContract);
        if (retrievalEnforced && !Objects.equals(enforcedContract, context.executionContract())) {
            throw new TurnContractEnforcementException(
                    "Retrieval runtime context loader did not preserve the enforced turn contract");
        }

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
                context.relevantLongTermMemories(),
                userId,
                turnId,
                chatSessionId,
                agentMessageBridge,
                policyProperties,
                context.executionMode(),
                context.executionContract(),
                approvalPort,
                ledgerPort
        );
    }
}
