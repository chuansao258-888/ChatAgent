package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.AgentExecutionModeResolver;
import com.yulong.chatagent.agent.runtime.AgentRunContext;
import com.yulong.chatagent.agent.runtime.AgentRunPolicy;
import com.yulong.chatagent.agent.runtime.AgentRunPolicyProperties;
import com.yulong.chatagent.agent.runtime.AgentRuntimeEngine;
import com.yulong.chatagent.agent.deepthink.DeepThinkRuntimeEngine;
import com.yulong.chatagent.agent.runtime.CurrentChatSessionHolder;
import com.yulong.chatagent.agent.runtime.CurrentIntentResolutionHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnKnowledgeHitHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnExecutionContractHolder;
import com.yulong.chatagent.agent.runtime.ReactRuntimeEngine;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
/**
 * 单次会话轮次里的有状态 Agent 运行门面。
 * <p>
 * 每次用户消息都会创建一个新的 {@code ChatAgent}，它负责：
 * <ul>
 *   <li>运行时上下文的绑定和清理（ThreadLocal、记忆窗口等）</li>
 *   <li>运行模式解析（ReAct / DeepThink）</li>
 *   <li>委托给具体的 {@link AgentRuntimeEngine} 执行循环</li>
 *   <li>错误处理</li>
 * </ul>
 * <p>
 * 实际的 ReAct 或 DeepThink 循环逻辑在对应的 Engine 实现中，
 * ChatAgent 本身不包含循环逻辑。
 */
public class ChatAgent {
    private static final Integer DEFAULT_MAX_MESSAGES = 80;
    private static final int RUNTIME_MEMORY_SLACK = 4;

    private final String agentId;
    private final String name;
    private final String chatSessionId;
    private final String turnId;
    private final AgentRuntimeEngine runtimeEngine;
    private final AgentRunContext runContext;
    private final AgentExecutionMode executionMode;

    public ChatAgent() {
        // Serialization-only constructor; production code should use ChatAgentFactory.
        this.agentId = null;
        this.name = null;
        this.chatSessionId = null;
        this.turnId = null;
        this.runtimeEngine = null;
        this.runContext = null;
        this.executionMode = AgentExecutionMode.REACT;
    }

    public ChatAgent(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     PromptLoader promptLoader,
                     LLMService llmService,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     String sessionFileSummary,
                     String sessionSummary,
                     String relevantLongTermMemories,
                     String userId,
                     String turnId,
                     String chatSessionId,
                     AgentMessageBridge messageBridge,
                     AgentRunPolicyProperties policyProperties) {
        this(agentId, name, description, systemPrompt, promptLoader, llmService,
                maxMessages, memory, availableTools, sessionFileSummary, sessionSummary,
                relevantLongTermMemories, userId, turnId, chatSessionId, messageBridge,
                policyProperties, AgentExecutionMode.REACT);
    }

    public ChatAgent(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     PromptLoader promptLoader,
                     LLMService llmService,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     String sessionFileSummary,
                     String sessionSummary,
                     String relevantLongTermMemories,
                     String userId,
                     String turnId,
                     String chatSessionId,
                     AgentMessageBridge messageBridge,
                     AgentRunPolicyProperties policyProperties,
                     AgentExecutionMode executionMode) {
        this(agentId, name, description, systemPrompt, promptLoader, llmService,
                maxMessages, memory, availableTools, sessionFileSummary, sessionSummary,
                relevantLongTermMemories, userId, turnId, chatSessionId, messageBridge,
                policyProperties, executionMode, null);
    }

    public ChatAgent(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     PromptLoader promptLoader,
                     LLMService llmService,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     String sessionFileSummary,
                     String sessionSummary,
                     String relevantLongTermMemories,
                     String userId,
                     String turnId,
                     String chatSessionId,
                     AgentMessageBridge messageBridge,
                     AgentRunPolicyProperties policyProperties,
                     AgentExecutionMode executionMode,
                     com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract executionContract) {
        this.agentId = agentId;
        this.name = name;
        this.chatSessionId = chatSessionId;
        this.turnId = turnId;
        this.executionMode = AgentExecutionModeResolver.resolve(executionMode, policyProperties);

        // Resolve summaries with fallbacks
        String resolvedSessionFileSummary = StringUtils.hasText(sessionFileSummary)
                ? sessionFileSummary
                : promptLoader.load(PromptConstants.FALLBACK_SESSION_FILES);
        String resolvedSessionSummary = StringUtils.hasText(sessionSummary)
                ? sessionSummary
                : promptLoader.load(PromptConstants.FALLBACK_SESSION_SUMMARY);
        String resolvedRelevantLongTermMemories = StringUtils.hasText(relevantLongTermMemories)
                ? relevantLongTermMemories
                : "";

        List<ToolCallback> resolvedTools = availableTools == null ? List.of() : List.copyOf(availableTools);

        // Build chat memory window
        List<Message> initialMemory = memory == null ? List.of() : memory;
        int configuredMaxMessages = maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages;
        int requiredCapacity = initialMemory.size() + RUNTIME_MEMORY_SLACK + (StringUtils.hasLength(systemPrompt) ? 1 : 0);

        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(Math.max(configuredMaxMessages, requiredCapacity))
                .build();
        if (StringUtils.hasLength(systemPrompt)) {
            chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }
        chatMemory.add(chatSessionId, initialMemory);

        // Disable Spring AI auto tool execution
        ToolCallingChatOptions chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolContext(buildToolContext(userId, chatSessionId, turnId, this.executionMode))
                .build();

        // Build run policy
        AgentRunPolicy policy;
        if (this.executionMode == AgentExecutionMode.DEEPTHINK && policyProperties != null
                && policyProperties.getDeepthink().isEnabled()) {
            policy = AgentRunPolicy.deepthink(policyProperties);
        } else {
            policy = AgentRunPolicy.react(policyProperties);
        }

        // Build immutable run context
        this.runContext = new AgentRunContext(
                agentId,
                name,
                chatSessionId,
                turnId,
                systemPrompt,
                promptLoader,
                llmService,
                chatMemory,
                chatOptions,
                resolvedTools,
                resolvedSessionFileSummary,
                resolvedRelevantLongTermMemories,
                messageBridge,
                policy,
                this.executionMode,
                executionContract
        );

        // Select runtime engine based on execution mode
        if (this.executionMode == AgentExecutionMode.DEEPTHINK && policyProperties != null
                && policyProperties.getDeepthink().isEnabled()) {
            this.runtimeEngine = new DeepThinkRuntimeEngine(this.runContext);
        } else {
            this.runtimeEngine = new ReactRuntimeEngine(this.runContext);
        }
    }

    /**
     * Backward-compatible constructor that uses default policy values.
     * Preserves the existing call sites that don't pass AgentRunPolicyProperties.
     */
    public ChatAgent(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     PromptLoader promptLoader,
                     LLMService llmService,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     String sessionFileSummary,
                     String sessionSummary,
                     String relevantLongTermMemories,
                     String userId,
                     String turnId,
                     String chatSessionId,
                     AgentMessageBridge messageBridge) {
        this(agentId, name, description, systemPrompt, promptLoader, llmService,
                maxMessages, memory, availableTools, sessionFileSummary, sessionSummary,
                relevantLongTermMemories, userId, turnId, chatSessionId, messageBridge,
                new AgentRunPolicyProperties());
    }

    private Map<String, Object> buildToolContext(String userId,
                                                 String chatSessionId,
                                                 String turnId,
                                                 AgentExecutionMode executionMode) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (StringUtils.hasText(userId)) {
            context.put("userId", userId);
        }
        if (StringUtils.hasText(chatSessionId)) {
            context.put("sessionId", chatSessionId);
        }
        if (StringUtils.hasText(turnId)) {
            context.put("turnId", turnId);
        }
        context.put("executionMode", executionMode == null ? AgentExecutionMode.REACT.name() : executionMode.name());
        return context;
    }

    /**
     * 运行 Agent，委托给具体的运行时引擎执行循环。
     * <p>
     * ChatAgent 本身只负责 ThreadLocal 绑定/清理和错误包装，
     * 实际的 ReAct 或 DeepThink 循环由 engine.run() 完成。
     */
    public AgentRunResult run() {
        log.info("ChatAgent facade run started: traceId={}, agentId={}, sessionId={}, executionMode={}",
                TraceContext.getTraceId(), agentId, chatSessionId, executionMode);

        try {
            if (this.runtimeEngine == null || this.runContext == null) {
                throw new IllegalStateException("ChatAgent must be created through ChatAgentFactory before run()");
            }
            // 绑定 ThreadLocal：工具通过这些获取 session/turn 等后端上下文。
            CurrentTurnKnowledgeHitHolder.reset();
            CurrentChatSessionHolder.set(this.chatSessionId);
            CurrentTurnHolder.set(this.turnId);
            CurrentTurnExecutionContractHolder.set(this.runContext.executionContract());

            return this.runtimeEngine.run(this.runContext);
        } catch (AgentRunException e) {
            // 已经是包装过的异常，直接向上抛
            throw e;
        } catch (Exception e) {
            // 兜底包装：正常情况下 engine 内部已经包装好了
            long durationMs = 0;
            throw new AgentRunException(
                    "Unexpected error in agent facade",
                    e,
                    AgentRunResult.failure(durationMs, CurrentTurnKnowledgeHitHolder.isKnowledgeHit(), e)
            );
        } finally {
            // Servlet/MQ 线程会被复用，ThreadLocal 不清理会把上一个用户的上下文泄漏给下一个请求。
            CurrentTurnKnowledgeHitHolder.clear();
            CurrentIntentResolutionHolder.clear();
            CurrentTurnExecutionContractHolder.clear();
            CurrentTurnHolder.clear();
            CurrentChatSessionHolder.clear();
        }
    }

    @Override
    public String toString() {
        return "ChatAgent {" +
                "name = " + name + ",\n" +
                "description = " + (runContext != null ? "" : "n/a") + ",\n" +
                "agentId = " + agentId + "}";
    }
}
