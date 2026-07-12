package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.tools.ToolDispatchContext;
import com.yulong.chatagent.agent.tools.ToolResponseNormalizer;
import com.yulong.chatagent.agent.tools.ToolExecutionLedgerPort;
import com.yulong.chatagent.agent.tools.ToolArgumentCanonicalizer;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;

import java.util.List;

@Slf4j
/**
 * ReAct 循环里的“行动阶段”封装。
 * <p>
 * 模型只会产出 tool_call 描述；这里负责调用 Spring AI 的工具执行器，
 * 得到 tool_response 后再把完整对话历史写回 Agent 记忆。
 */
public class AgentToolExecutionEngine {

    private final AgentToolExecutionCoordinator coordinator;
    private final ChatOptions chatOptions;
    private final String turnId;
    private final AgentMessageBridge messageBridge;
    private final int maxToolCallsPerStep;
    // ARRB Phase 1（cross-review F-1/F-2/F-4/F-5）：可选的运行时派发上下文。
    // 由运行时引擎在装配时通过 {@link #configureDispatchContext} 注入；缺失时退回基线行为。
    private ToolDispatchContext dispatchContext = ToolDispatchContext.empty();

    public AgentToolExecutionEngine(List<ToolCallback> availableTools,
                             ChatOptions chatOptions,
                             String turnId,
                             AgentMessageBridge messageBridge) {
        this(availableTools, chatOptions, turnId, messageBridge,
                AgentToolExecutionCoordinator.DEFAULT_MAX_CALLS_PER_STEP);
    }

    /**
     * ARRB Phase 1（cross-review F-8）：允许上层运行时把配置的 per-step cap 传进来，
     * 这样协调器在 ReAct 与 DeepThink 直连路径上用同一份配置上限做 preflight 截断，
     * 而不是用硬编码默认值。
     */
    public AgentToolExecutionEngine(List<ToolCallback> availableTools,
                             ChatOptions chatOptions,
                             String turnId,
                             AgentMessageBridge messageBridge,
                             int maxToolCallsPerStep) {
        this.coordinator = new AgentToolExecutionCoordinator(availableTools, maxToolCallsPerStep);
        this.chatOptions = chatOptions;
        this.turnId = turnId;
        this.messageBridge = messageBridge;
        this.maxToolCallsPerStep = maxToolCallsPerStep;
    }

    /**
     * ARRB Phase 1（cross-review F-1/F-2/F-4/F-5）：注入运行时派发上下文。
     * 由 ReactRuntimeEngine/DeepThinkRuntimeEngine 在装配时调用一次，提供 approval/ledger/
     * budget/descriptor。注入后，{@link #execute} 与 {@link #executeToolCallsDirect}
     * 走 coordinator 的确认门 + ledger CAS + 预算 + 重试路径。
     */
    public void configureDispatchContext(ToolDispatchContext dispatchContext) {
        this.dispatchContext = dispatchContext == null ? ToolDispatchContext.empty() : dispatchContext;
    }

    /**
     * 执行模型请求的工具调用，并把工具响应写回短期记忆。
     *
     * @param chatMemory 当前 Agent 的窗口记忆
     * @param chatSessionId 当前会话 ID
     * @param chatResponse 上一步模型输出
     * @return 如果工具响应里出现 terminate，返回 {@code true} 让外层循环停止
     */
    public boolean execute(ChatMemory chatMemory, String chatSessionId, ChatResponse chatResponse) {
        return executeWithOutcome(chatMemory, chatSessionId, chatResponse).terminated();
    }

    public ExecutionOutcome executeWithOutcome(ChatMemory chatMemory,
                                               String chatSessionId,
                                               ChatResponse chatResponse) {
        Assert.notNull(chatResponse, "Last chat client response cannot be null");

        // 没有工具调用时不做任何事；正常情况下外层 step() 已经挡住这个分支。
        if (!chatResponse.hasToolCalls()) {
            return ExecutionOutcome.completed(false, null);
        }

        long startTime = System.nanoTime();
        // ToolCallingManager 需要当前完整历史和 chatOptions，才能把 assistant tool_call
        // 与实际工具回调匹配起来，并生成规范化后的 conversationHistory。
        Prompt prompt = Prompt.builder()
                .messages(chatMemory.get(chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        // ARRB Phase 1（F-1/F-2/F-4/F-5）：当运行时注入了派发上下文（含 sessionId/turnId）时，
        // 走 coordinator 的确认门 + ledger CAS + 预算 + 重试路径；否则退回基线 preflight+per-call。
        ToolDispatchContext effectiveContext = withSessionId(dispatchContext, chatSessionId);
        if (!advancedDispatchConfigured(effectiveContext)) {
            ToolExecutionResult baseline = this.coordinator.execute(prompt, chatResponse);
            return completeBaselineExecution(chatMemory, chatSessionId, baseline, startTime);
        }
        AgentToolExecutionCoordinator.CoordinatedExecution coordinated =
                this.coordinator.executeCoordinated(prompt, chatResponse, effectiveContext);
        ToolExecutionResult toolExecutionResult = coordinated.executionResult();

        // ARRB-AC-005 (cross-review F-3): 在写回 memory（即下一轮 prompt 上下文）之前就把
        // 每个 callback 结果规范化到 12,000 UTF-16 code units，避免超大工具结果撑爆下一轮
        // prompt。规范化必须在写 memory 之前完成，而不是只在发布副本上做。
        List<org.springframework.ai.chat.messages.Message> history =
                new java.util.ArrayList<>(toolExecutionResult.conversationHistory());
        if (!history.isEmpty()
                && history.get(history.size() - 1) instanceof ToolResponseMessage rawResponseMessage) {
            ToolResponseMessage normalized = ToolResponseNormalizer.normalizeMessage(rawResponseMessage);
            history.set(history.size() - 1, normalized);
        }

        // 采用“全量替换”而不是手动 append：写回的 history 已是规范化后的版本，
        // 保证下一轮模型看到的 tool observation 都是有界的。
        chatMemory.clear(chatSessionId);
        chatMemory.add(chatSessionId, history);

        // history 末尾现在就是规范化后的工具响应消息，后续逐个落库并推给前端。
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) history.get(history.size() - 1);

        // ARRB ARRB-AC-031: 完整工具参数/结果不进入 INFO/WARN 日志。这里只记录 trace/会话、
        // 工具数量、耗时与稳定结果状态（success），便于排查与统计，而不泄露原始工具响应正文。
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        log.info("Tool execution completed: traceId={}, sessionId={}, responses={}, durationMs={}, outcome=success",
                TraceContext.getTraceId(),
                chatSessionId,
                toolResponseMessage.getResponses().size(),
                durationMs);
        persistCommittedResponses(effectiveContext, coordinated, toolResponseMessage,
                false, null, null);

        // terminate 工具目前虽可能被禁用，但保留这个终止协议，方便未来重新开放。
        boolean terminated = toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"));
        String stopReason = stopReason(toolResponseMessage);
        return new ExecutionOutcome(terminated, "CONFIRMATION_REQUIRED".equals(stopReason),
                stopReason != null && !"CONFIRMATION_REQUIRED".equals(stopReason),
                stopReason, toolResponseMessage);
    }

    /**
     * Execute tool calls without touching ChatMemory. Used by DeepThink which
     * manages its own local conversation history. This is the shared execution
     * path — DeepThink must NOT call {@code ToolCallback.call()} directly.
     *
     * @param assistantMessage the model output containing tool calls
     * @return the tool response message, or null if execution failed
     */
    public ToolResponseMessage executeToolCallsDirect(org.springframework.ai.chat.messages.AssistantMessage assistantMessage) {
        return executeToolCallsDirectWithOutcome(assistantMessage).response();
    }

    public ExecutionOutcome executeToolCallsDirectWithOutcome(
            org.springframework.ai.chat.messages.AssistantMessage assistantMessage) {
        return executeToolCallsDirectWithOutcome(assistantMessage, null, null);
    }

    public ExecutionOutcome executeToolCallsDirectWithOutcome(
            org.springframework.ai.chat.messages.AssistantMessage assistantMessage,
            String deepThinkPhase,
            String planStepId) {
        return executeToolCallsDirectWithOutcome(
                assistantMessage, dispatchContext.sessionId(), deepThinkPhase, planStepId);
    }

    public ExecutionOutcome executeToolCallsDirectWithOutcome(
            org.springframework.ai.chat.messages.AssistantMessage assistantMessage,
            String chatSessionId,
            String deepThinkPhase,
            String planStepId) {
        // DeepThink 直连路径同样走 ToolResponseNormalizer：模型可见结果统一有界（ARRB-AC-005）。
        // 当运行时注入了派发上下文时，走 coordinator 的确认门 + ledger CAS + 预算路径。
        if (!advancedDispatchConfigured(dispatchContext)) {
            com.yulong.chatagent.agent.tools.ToolCallPreflight.ToolCallPreflightResult preflight =
                    new com.yulong.chatagent.agent.tools.ToolCallPreflight(maxToolCallsPerStep)
                            .normalize(assistantMessage);
            org.springframework.ai.chat.messages.AssistantMessage normalizedAssistant =
                    preflight.assistantMessage();
            ToolResponseMessage response = ToolResponseNormalizer.normalizeMessage(
                    coordinator.executeDirect(normalizedAssistant, chatOptions));
            messageBridge.persistInternalAssistantToolCalls(
                    chatSessionId, turnId, normalizedAssistant,
                    deepThinkPhase, planStepId);
            messageBridge.persistInternalToolResponses(
                    chatSessionId, turnId, response,
                    deepThinkPhase, planStepId);
            return ExecutionOutcome.completed(false, response);
        }
        AgentToolExecutionCoordinator.CoordinatedExecution coordinated =
                coordinator.executeDirectCoordinated(assistantMessage, chatOptions, dispatchContext);
        ToolResponseMessage response = ToolResponseNormalizer.normalizeMessage(coordinated.response());
        org.springframework.ai.chat.messages.AssistantMessage normalizedAssistant =
                (org.springframework.ai.chat.messages.AssistantMessage)
                        coordinated.executionResult().conversationHistory().get(0);
        messageBridge.persistInternalAssistantToolCalls(
                chatSessionId, turnId, normalizedAssistant,
                deepThinkPhase, planStepId);
        persistCommittedResponses(dispatchContext, coordinated, response, true,
                deepThinkPhase, planStepId);
        String stopReason = stopReason(response);
        return new ExecutionOutcome(false, "CONFIRMATION_REQUIRED".equals(stopReason),
                stopReason != null && !"CONFIRMATION_REQUIRED".equals(stopReason),
                stopReason, response);
    }

    private void persistCommittedResponses(ToolDispatchContext context,
                                           AgentToolExecutionCoordinator.CoordinatedExecution coordinated,
                                           ToolResponseMessage normalized,
                                           boolean internal,
                                           String deepThinkPhase,
                                           String planStepId) {
        java.util.Map<String, AgentToolExecutionCoordinator.TerminalCommit> commits =
                coordinated.terminalCommits().stream().collect(java.util.stream.Collectors.toMap(
                        AgentToolExecutionCoordinator.TerminalCommit::toolCallId,
                        java.util.function.Function.identity()));
        ToolExecutionLedgerPort ledger = context.ledgerPort();
        for (ToolResponseMessage.ToolResponse response : normalized.getResponses()) {
            AgentToolExecutionCoordinator.TerminalCommit commit = commits.get(response.id());
            if (commit == null) {
                messageBridge.persistAndPublish(context.sessionId(), turnId,
                        ToolResponseMessage.builder().responses(List.of(response)).build());
                continue;
            }
            if (ledger == null) {
                throw new ToolExecutionException("Tool ledger is required after dispatch");
            }
            ToolExecutionLedgerPort.PersistedToolResponse persisted = ledger.commitTerminalResponse(
                    commit.executionKey(),
                    "DISPATCHING",
                    new ToolExecutionLedgerPort.TerminalUpdate(
                            commit.terminalState(), null,
                            ToolArgumentCanonicalizer.sha256(response.responseData()),
                            commit.safeErrorCode(), commit.expectedAttempt()),
                    new ToolExecutionLedgerPort.ToolResponseCommitRequest(
                            context.sessionId(), turnId, response, internal,
                            deepThinkPhase, planStepId));
            messageBridge.publishPersistedToolResponse(persisted);
        }
    }

    /**
     * 把当前 chatSessionId 并入派发上下文（运行时注入的上下文可能只带 turnId），
     * 使 approval stage 与 journal execution key 都能落到正确的 session。
     */
    private static ToolDispatchContext withSessionId(ToolDispatchContext ctx, String sessionId) {
        if (ctx == null) {
            return new ToolDispatchContext(sessionId, null, null, null, null, null);
        }
        String existingSession = ctx.sessionId();
        if (existingSession != null) {
            return ctx;
        }
        return new ToolDispatchContext(sessionId, ctx.turnId(), ctx.approvalPort(),
                ctx.ledgerPort(), ctx.budget(), ctx.descriptorResolver(),
                ctx.approvedProposal(), ctx.policyVersion(), ctx.contractVersion());
    }

    private static String stopReason(ToolResponseMessage response) {
        if (response == null || response.getResponses() == null) {
            return "TOOL_RESPONSE_MISSING";
        }
        for (ToolResponseMessage.ToolResponse item : response.getResponses()) {
            String data = item.responseData();
            if (data == null) {
                continue;
            }
            if (data.startsWith("CONFIRMATION_REQUIRED")) return "CONFIRMATION_REQUIRED";
            if (data.startsWith("TOOL_APPROVAL_STALE")) return "TOOL_APPROVAL_STALE";
            if (data.startsWith("CONFIRMATION_UNAVAILABLE")) return "CONFIRMATION_UNAVAILABLE";
            if (data.startsWith("TOOL_DISPATCH_BUDGET_EXHAUSTED")) return "TOOL_DISPATCH_BUDGET_EXHAUSTED";
            if (data.startsWith("TOOL_PROPOSAL_BUDGET_EXHAUSTED")) return "TOOL_PROPOSAL_BUDGET_EXHAUSTED";
            if (data.startsWith("TOOL_DEADLINE_")) return data.split(":", 2)[0];
        }
        return null;
    }

    private ExecutionOutcome completeBaselineExecution(ChatMemory chatMemory,
                                                        String chatSessionId,
                                                        ToolExecutionResult result,
                                                        long startTime) {
        List<org.springframework.ai.chat.messages.Message> history =
                new java.util.ArrayList<>(result.conversationHistory());
        ToolResponseMessage response = ToolResponseNormalizer.normalizeMessage(
                (ToolResponseMessage) history.get(history.size() - 1));
        history.set(history.size() - 1, response);
        chatMemory.clear(chatSessionId);
        chatMemory.add(chatSessionId, history);
        messageBridge.persistAndPublish(chatSessionId, turnId, response);
        log.info("Tool execution completed: traceId={}, sessionId={}, responses={}, durationMs={}, outcome=success",
                TraceContext.getTraceId(), chatSessionId, response.getResponses().size(),
                (System.nanoTime() - startTime) / 1_000_000);
        boolean terminated = response.getResponses().stream()
                .anyMatch(item -> "terminate".equals(item.name()));
        return ExecutionOutcome.completed(terminated, response);
    }

    private static boolean advancedDispatchConfigured(ToolDispatchContext context) {
        return context != null
                && context.approvalPort() != null
                && context.ledgerPort() != null
                && context.descriptorResolver() != null
                && context.budget() != null;
    }

    public record ExecutionOutcome(
            boolean terminated,
            boolean blocked,
            boolean partial,
            String stopReason,
            ToolResponseMessage response) {

        static ExecutionOutcome completed(boolean terminated, ToolResponseMessage response) {
            return new ExecutionOutcome(terminated, false, false, null, response);
        }
    }
}
