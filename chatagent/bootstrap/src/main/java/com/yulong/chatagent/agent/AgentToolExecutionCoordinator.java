package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.tools.PerCallToolExecutor;
import com.yulong.chatagent.agent.tools.ToolApprovalChallenge;
import com.yulong.chatagent.agent.tools.ToolApprovalPort;
import com.yulong.chatagent.agent.tools.ToolCallPreflight;
import com.yulong.chatagent.agent.tools.ToolDispatchContext;
import com.yulong.chatagent.agent.tools.ToolExecutionDescriptor;
import com.yulong.chatagent.agent.tools.ToolExecutionDescriptorResolver;
import com.yulong.chatagent.agent.tools.ToolExecutionLedgerPort;
import com.yulong.chatagent.agent.tools.DeadlineMode;
import com.yulong.chatagent.agent.tools.ToolArgumentCanonicalizer;
import com.yulong.chatagent.agent.tools.SafeJsonSchemaValidator;
import com.yulong.chatagent.agent.tools.DescribedToolCallback;
import com.yulong.chatagent.agent.runtime.CurrentToolDeadlineHolder;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * One Spring-AI tool execution boundary shared by ReAct and DeepThink.
 * <p>
 * ARRB Phase 1：每次执行先用共享 {@link ToolCallPreflight} 对模型的一批 tool call 做
 * 规范化与边界检查（batch cap、稳定 server id、参数字节上限、name 校验），再用
 * {@link PerCallToolExecutor} 做按调用的隔离执行：一个回调失败不再终止整批，
 * 每个 retained call 都得到恰好一条配对响应。
 * <p>
 * 该协调器只做 name 解析、preflight、per-call 隔离、配对；它不持有运行状态，
 * 也不直接做 ledger/approval/budget（那是运行时引擎在上层装配的职责）。
 */
public final class AgentToolExecutionCoordinator {

    private final ToolCallingManager toolCallingManager;
    private final List<ToolCallback> availableTools;
    private final int maxCallsPerStep;
    private final java.util.Map<String, ToolCallback> callbackByName;
    private final SafeJsonSchemaValidator schemaValidator =
            new SafeJsonSchemaValidator(new com.fasterxml.jackson.databind.ObjectMapper());

    public AgentToolExecutionCoordinator(List<ToolCallback> availableTools) {
        this(availableTools, DEFAULT_MAX_CALLS_PER_STEP);
    }

    /**
     * ARRB Phase 1（cross-review F-8）：允许上层把配置的 per-step cap 传入，
     * 替代硬编码默认值。
     */
    public AgentToolExecutionCoordinator(List<ToolCallback> availableTools, int maxCallsPerStep) {
        this.availableTools = sanitize(availableTools);
        this.maxCallsPerStep = maxCallsPerStep < 1 ? DEFAULT_MAX_CALLS_PER_STEP : maxCallsPerStep;
        this.toolCallingManager = ToolCallingManager.builder()
                .toolCallbackResolver(new StaticToolCallbackResolver(this.availableTools))
                .build();
        this.callbackByName = this.availableTools.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                callback -> callback.getToolDefinition().name(),
                java.util.function.Function.identity(),
                (left, right) -> left));
    }

    public ToolExecutionResult execute(Prompt prompt, ChatResponse response) {
        AssistantMessage assistantMessage = response.getResult().getOutput();
        // 没有工具调用时直接走原 Spring AI 路径（保持既有行为）。
        if (assistantMessage == null || assistantMessage.getToolCalls() == null
                || assistantMessage.getToolCalls().isEmpty()) {
            try {
                return toolCallingManager.executeToolCalls(prompt, response);
            } catch (RuntimeException exception) {
                throw new ToolExecutionException("Tool execution failed", exception);
            }
        }

        // ARRB Phase 1：preflight 规范化 + per-call 隔离执行，替换原"整批异常"语义。
        ToolCallPreflight preflight = new ToolCallPreflight(maxCallsPerStep);
        ToolCallPreflight.ToolCallPreflightResult preflightResult = preflight.normalize(assistantMessage);
        if (preflightResult.isEmpty()) {
            // preflight 后没有任何 retained call（极端情况），回退到原始 history。
            List<org.springframework.ai.chat.messages.Message> instructions =
                    prompt.getInstructions() != null ? prompt.getInstructions() : List.of();
            return () -> instructions;
        }
        ToolResponseMessage paired = PerCallToolExecutor.executePerCall(
                toolCallingManager, preflightResult, prompt.getOptions(), prompt);
        return () -> appendPaired(prompt, preflightResult.assistantMessage(), paired);
    }

    public ToolResponseMessage executeDirect(AssistantMessage assistantMessage,
                                             ChatOptions chatOptions) {
        if (assistantMessage == null || assistantMessage.getToolCalls() == null
                || assistantMessage.getToolCalls().isEmpty()) {
            throw new ToolExecutionException("Tool execution requires at least one tool call");
        }

        // DeepThink 直连路径同样走 preflight + per-call 隔离。
        ToolCallPreflight preflight = new ToolCallPreflight(maxCallsPerStep);
        ToolCallPreflight.ToolCallPreflightResult preflightResult = preflight.normalize(assistantMessage);
        if (preflightResult.isEmpty()) {
            throw new ToolExecutionException("Tool execution produced no retained tool calls after preflight");
        }
        Prompt prompt = Prompt.builder()
                .messages(List.of(preflightResult.assistantMessage()))
                .chatOptions(chatOptions)
                .build();
        return PerCallToolExecutor.executePerCall(toolCallingManager, preflightResult, chatOptions, prompt);
    }

    /** 暴露给上层运行时：按 model-facing name 解析 {@link ToolExecutionDescriptor}。 */
    public List<ToolCallback> availableCallbacks() {
        return availableTools;
    }

    /**
     * ARRB Phase 1（cross-review F-1/F-2/F-4/F-5）：带运行时派发上下文的执行入口。
     * <p>
     * 当 {@link ToolDispatchContext} 提供 approval/ledger/budget/descriptor 时，coordinator
     * 按下列顺序处理每个 preflight-retained call：
     * <ol>
     *   <li>消耗 proposal 预算；</li>
     *   <li>若 descriptor 需要确认（非 READ_ONLY），stage 一条 approval challenge，
     *       返回 CONFIRMATION_REQUIRED 配对响应，不派发（AC-008）；</li>
     *   <li>否则消耗 dispatch 预算，ledger prepare + CAS DISPATCHING，派发回调，
     *       ledger commitTerminal（AC-009），并对 READ_ONLY/IDEMPOTENT 的 typed 可重试失败
     *       允许一次有限重试（AC-006）。</li>
     * </ol>
     * 上下文缺失时退回到基线 preflight + per-call 隔离行为。
     */
    public ToolExecutionResult execute(Prompt prompt, ChatResponse response, ToolDispatchContext ctx) {
        return executeCoordinated(prompt, response, ctx).executionResult();
    }

    public CoordinatedExecution executeCoordinated(Prompt prompt,
                                                   ChatResponse response,
                                                   ToolDispatchContext ctx) {
        AssistantMessage assistantMessage = response.getResult().getOutput();
        if (assistantMessage == null || assistantMessage.getToolCalls() == null
                || assistantMessage.getToolCalls().isEmpty()) {
            ToolExecutionResult result = execute(prompt, response);
            return new CoordinatedExecution(result, null, List.of());
        }
        ToolCallPreflight preflight = new ToolCallPreflight(maxCallsPerStep);
        ToolCallPreflight.ToolCallPreflightResult preflightResult = preflight.normalize(assistantMessage);
        if (preflightResult.isEmpty()) {
            List<org.springframework.ai.chat.messages.Message> instructions =
                    prompt.getInstructions() != null ? prompt.getInstructions() : List.of();
            ToolExecutionResult result = () -> instructions;
            return new CoordinatedExecution(result, null, List.of());
        }
        ToolDispatchContext context = ctx == null ? ToolDispatchContext.empty() : ctx;
        DispatchBatch batch = dispatchWithContext(preflightResult, prompt, context);
        ToolExecutionResult result = () -> appendPaired(
                prompt, preflightResult.assistantMessage(), batch.response());
        return new CoordinatedExecution(result, batch.response(), batch.terminalCommits());
    }

    /**
     * ARRB Phase 1：DeepThink 直连路径的运行时上下文版本。语义与 {@link #execute} 的上下文版本一致。
     */
    public ToolResponseMessage executeDirect(AssistantMessage assistantMessage,
                                             ChatOptions chatOptions,
                                             ToolDispatchContext ctx) {
        return executeDirectCoordinated(assistantMessage, chatOptions, ctx).response();
    }

    public CoordinatedExecution executeDirectCoordinated(AssistantMessage assistantMessage,
                                                         ChatOptions chatOptions,
                                                         ToolDispatchContext ctx) {
        if (assistantMessage == null || assistantMessage.getToolCalls() == null
                || assistantMessage.getToolCalls().isEmpty()) {
            throw new ToolExecutionException("Tool execution requires at least one tool call");
        }
        ToolCallPreflight preflight = new ToolCallPreflight(maxCallsPerStep);
        ToolCallPreflight.ToolCallPreflightResult preflightResult = preflight.normalize(assistantMessage);
        if (preflightResult.isEmpty()) {
            throw new ToolExecutionException("Tool execution produced no retained tool calls after preflight");
        }
        ToolDispatchContext context = ctx == null ? ToolDispatchContext.empty() : ctx;
        Prompt prompt = Prompt.builder()
                .messages(List.of(preflightResult.assistantMessage()))
                .chatOptions(chatOptions)
                .build();
        DispatchBatch batch = dispatchWithContext(preflightResult, prompt, context);
        ToolExecutionResult result = () -> List.of(
                preflightResult.assistantMessage(), batch.response());
        return new CoordinatedExecution(result, batch.response(), batch.terminalCommits());
    }

    /**
     * F-1/F-2/F-4：按 preflight 顺序逐 call 处理，应用确认门、ledger CAS、预算与重试。
     */
    private DispatchBatch dispatchWithContext(ToolCallPreflight.ToolCallPreflightResult preflightResult,
                                                    Prompt prompt,
                                                    ToolDispatchContext context) {
        ToolExecutionDescriptorResolver descriptors = context.maybeDescriptorResolver().orElse(null);
        ToolApprovalPort approval = context.maybeApprovalPort().orElse(null);
        ToolExecutionLedgerPort ledger = context.maybeLedgerPort().orElse(null);

        java.util.List<ToolResponseMessage.ToolResponse> responses = new java.util.ArrayList<>();
        java.util.List<TerminalCommit> terminalCommits = new java.util.ArrayList<>();
        boolean stoppedForApproval = false;
        for (ToolCallPreflight.NormalizedToolCall call : preflightResult.calls()) {
            // 首个需要确认的 effect 已 stage 后，后续 call 落 BATCH_STOPPED_FOR_APPROVAL（不派发）。
            if (stoppedForApproval) {
                responses.add(safeResponse(call, "BATCH_STOPPED_FOR_APPROVAL"));
                continue;
            }
            // 消耗 proposal 预算（AC-007）：在 preflight 之后、派发之前。
            if (context.maybeBudget().isPresent() && !context.maybeBudget().get().consumeToolProposal()) {
                responses.add(safeResponse(call, "TOOL_PROPOSAL_BUDGET_EXHAUSTED"));
                continue;
            }
            // preflight violation：不派发，落 typed 修正 observation。
            if (call.hasViolation()) {
                responses.add(safeResponse(call, violationMessage(call)));
                continue;
            }
            String canonicalArguments;
            try {
                canonicalArguments = ToolArgumentCanonicalizer.canonicalize(call.arguments());
            } catch (IllegalArgumentException malformed) {
                responses.add(safeResponse(call, "TOOL_ARGUMENTS_MALFORMED"));
                continue;
            }
            String fingerprint = call.name() + ":" + ToolArgumentCanonicalizer.sha256(canonicalArguments);
            if (context.maybeBudget().isPresent()
                    && !context.maybeBudget().get().recordProposalFingerprint(fingerprint)) {
                responses.add(safeResponse(call, "REPEATED_CALL_BLOCKED"));
                continue;
            }
            ToolCallback callback = callbackByName.get(call.name());
            if (callback == null) {
                responses.add(safeResponse(call, "TOOL_UNKNOWN"));
                continue;
            }
            ToolExecutionDescriptor preflightDescriptor = descriptors == null
                    ? ToolExecutionDescriptor.unknown(call.name()) : descriptors.resolve(call.name());
            SafeJsonSchemaValidator.SchemaTrust trust =
                    callback instanceof DescribedToolCallback
                            && preflightDescriptor.deadlineMode() == DeadlineMode.ENFORCED
                            ? SafeJsonSchemaValidator.SchemaTrust.TRUSTED_BUILTIN
                            : SafeJsonSchemaValidator.SchemaTrust.UNTRUSTED_MCP;
            SafeJsonSchemaValidator.Result schemaResult = schemaValidator.validate(
                    callback.getToolDefinition().inputSchema(), canonicalArguments, trust);
            if (!schemaResult.isValid()) {
                responses.add(safeResponse(call, "TOOL_SCHEMA_" + schemaResult.name()));
                continue;
            }
            // F-1（AC-008）：非只读/未知调用首次提案不派发，stage approval challenge。
            ToolExecutionDescriptor descriptor = descriptors == null ? null : descriptors.resolve(call.name());
            boolean needsConfirmation = descriptor == null || descriptor.requiresConfirmation();
            if (needsConfirmation && !matchesApprovedProposal(call, descriptor, context)) {
                if (context.approvedProposal() != null) {
                    responses.add(safeResponse(call, "TOOL_APPROVAL_STALE"));
                    stoppedForApproval = true;
                    continue;
                }
                if (approval == null || context.sessionId() == null) {
                    responses.add(safeResponse(call, "CONFIRMATION_UNAVAILABLE"));
                    stoppedForApproval = true;
                    continue;
                }
                ToolApprovalChallenge challenge = approval.stageProposal(
                        new ToolApprovalPort.ToolApprovalRequest(
                                context.sessionId(), context.turnId(), call.name(), call.arguments(),
                                descriptor == null ? null : descriptor.stableHash(),
                                context.policyVersion(), context.contractVersion()));
                responses.add(approvalResponse(call, challenge));
                stoppedForApproval = true;
                continue;
            }
            if (descriptor == null || descriptor.deadlineMode() != DeadlineMode.ENFORCED) {
                responses.add(safeResponse(call, "TOOL_DEADLINE_UNSUPPORTED"));
                continue;
            }
            // F-2/F-4（AC-006/009）：派发 + ledger CAS + 一次有限重试。
            DispatchOutcome dispatched = dispatchOne(call, prompt, context, descriptors, ledger);
            responses.add(dispatched.response());
            if (dispatched.terminalCommit() != null) {
                terminalCommits.add(dispatched.terminalCommit());
            }
        }
        return new DispatchBatch(
                ToolResponseMessage.builder().responses(responses).build(),
                List.copyOf(terminalCommits));
    }

    /** 单个可派发 call 的执行：预算、ledger prepare/CAS/commit、一次有限重试。 */
    private DispatchOutcome dispatchOne(ToolCallPreflight.NormalizedToolCall call,
                                                         Prompt prompt,
                                                         ToolDispatchContext context,
                                                         ToolExecutionDescriptorResolver descriptors,
                                                         ToolExecutionLedgerPort ledger) {
        // 消耗实际派发预算（AC-007）。
        if (context.maybeBudget().isPresent() && !context.maybeBudget().get().consumeActualDispatch()) {
            return DispatchOutcome.withoutCommit(safeResponse(call, "TOOL_DISPATCH_BUDGET_EXHAUSTED"));
        }

        // F-2（AC-009）：ledger prepare + CAS DISPATCHING。execution key 对只读调用是
        // turnId + toolName + SHA-256(canonical args)；SUCCEEDED 行避免重复派发（F-4 的一部分）。
        String executionKey = executionKey(context, call);
        if (ledger != null && executionKey != null) {
            java.util.Optional<ToolExecutionLedgerPort.JournalEntry> existing = ledger.findByExecutionKey(executionKey);
            if (existing.isPresent() && "SUCCEEDED".equals(existing.get().state())) {
                if (existing.get().responseMessageId() == null) {
                    return DispatchOutcome.withoutCommit(
                            safeResponse(call, "TOOL_LEDGER_INTEGRITY_FAILURE"));
                }
                java.util.Optional<ToolResponseMessage.ToolResponse> recovered =
                        ledger.loadCommittedResponse(executionKey);
                if (recovered.isEmpty()) {
                    return DispatchOutcome.withoutCommit(
                            safeResponse(call, "TOOL_LEDGER_INTEGRITY_FAILURE"));
                }
                ToolResponseMessage.ToolResponse stored = recovered.get();
                return DispatchOutcome.withoutCommit(new ToolResponseMessage.ToolResponse(
                        call.callId(), call.name(), stored.responseData()));
            }
            ToolExecutionDescriptor descriptor = descriptors != null ? descriptors.resolve(call.name()) : null;
            String effectClass = descriptor != null ? descriptor.effectClass().name() : "UNKNOWN";
            ToolExecutionLedgerPort.JournalEntry entry = new ToolExecutionLedgerPort.JournalEntry(
                    null, executionKey, context.sessionId(), context.turnId(), null,
                    null, call.callId(), call.name(), sha256(call.arguments()), effectClass,
                    1, "PREPARED", null, null, null, null, null, null, null);
            java.util.Optional<ToolExecutionLedgerPort.JournalEntry> prepared = ledger.prepare(entry);
            if (prepared.isEmpty()) {
                // execution key 已存在且非 SUCCEEDED：视为进行中/重复，不重复派发。
                return DispatchOutcome.withoutCommit(safeResponse(call, "REPEATED_CALL_BLOCKED"));
            }
            if (!ledger.tryDispatch(executionKey, "PREPARED", 1, java.time.LocalDateTime.now())) {
                // CAS 失败：状态已被其它进程推进。
                return DispatchOutcome.withoutCommit(safeResponse(call, "REPEATED_CALL_BLOCKED"));
            }
        }

        // 实际派发：第一次尝试。executeSingle 返回该 call 的唯一配对响应。
        ToolResponseMessage.ToolResponse outcome = executeSingle(call, prompt, context);

        // F-4（AC-006）：只读/idempotent 的 typed 失败允许一次有限重试。
        ToolExecutionDescriptor descriptor = descriptors != null ? descriptors.resolve(call.name()) : null;
        boolean retryable = descriptor != null && descriptor.retryable();
        int terminalAttempt = 1;
        if (retryable && isRetryableFailure(outcome)
                && ledger != null && executionKey != null
                && ledger.prepareRetry(executionKey, 1, 2, "RETRYABLE_FAILURE")
                && context.maybeBudget().map(b -> b.consumeActualDispatch()).orElse(false)
                && ledger.tryDispatch(executionKey, "PREPARED", 2, java.time.LocalDateTime.now())) {
            outcome = executeSingle(call, prompt, context);
            terminalAttempt = 2;
        }

        if (ledger != null && executionKey != null) {
            boolean succeeded = !isFailure(outcome);
            boolean ambiguous = !succeeded && descriptor != null
                    && (descriptor.effectClass() == com.yulong.chatagent.agent.tools.ToolEffectClass.NON_IDEMPOTENT
                    || descriptor.effectClass() == com.yulong.chatagent.agent.tools.ToolEffectClass.UNKNOWN);
            return new DispatchOutcome(outcome, new TerminalCommit(
                    executionKey,
                    call.callId(),
                    succeeded ? "SUCCEEDED" : ambiguous ? "OUTCOME_UNKNOWN" : "FAILED_KNOWN",
                    sha256(outcome.responseData()),
                    succeeded ? null : ambiguous ? "CALLBACK_OUTCOME_UNKNOWN" : "CALLBACK_FAILURE",
                    terminalAttempt));
        }
        return DispatchOutcome.withoutCommit(outcome);
    }

    /** 派发单个 preflight-retained call，返回它的配对响应（成功或 safe 错误 observation）。 */
    private ToolResponseMessage.ToolResponse executeSingle(ToolCallPreflight.NormalizedToolCall call,
                                                            Prompt prompt,
                                                            ToolDispatchContext context) {
        long remainingMs = context.maybeBudget()
                .map(com.yulong.chatagent.agent.runtime.AgentRunBudget::remainingElapsedMs)
                .orElse(Long.MAX_VALUE);
        try {
            CurrentToolDeadlineHolder.bindRemainingMillis(remainingMs == Long.MAX_VALUE
                    ? java.time.Duration.ofMinutes(5).toMillis() : remainingMs);
            ToolResponseMessage single = PerCallToolExecutor.executePerCall(
                    toolCallingManager,
                    new ToolCallPreflight.ToolCallPreflightResult(
                            null, java.util.List.of(call), false),
                    prompt.getOptions(), prompt);
            if (single.getResponses() == null || single.getResponses().isEmpty()) {
                return safeResponse(call, "Tool execution produced no paired response");
            }
            return single.getResponses().get(0);
        } catch (CurrentToolDeadlineHolder.ToolDeadlineExceededException exhausted) {
            return safeResponse(call, "TOOL_DEADLINE_EXCEEDED");
        } finally {
            CurrentToolDeadlineHolder.clear();
        }
    }

    private boolean isRetryableFailure(ToolResponseMessage.ToolResponse response) {
        String data = response.responseData();
        if (data == null) return false;
        String normalized = data.toLowerCase(java.util.Locale.ROOT);
        return data.startsWith("TOOL_RETRYABLE_FAILURE")
                || normalized.contains("temporarily unavailable")
                || normalized.contains("timed out")
                || normalized.contains("rate limit");
    }

    private boolean isFailure(ToolResponseMessage.ToolResponse response) {
        String data = response.responseData();
        return data == null || data.startsWith("Error:") || data.startsWith("TOOL_")
                || data.startsWith("CONFIRMATION_") || data.startsWith("REPEATED_");
    }

    private static String executionKey(ToolDispatchContext context, ToolCallPreflight.NormalizedToolCall call) {
        if (context.turnId() == null) {
            return null;
        }
        String canonical = ToolArgumentCanonicalizer.canonicalize(call.arguments());
        if (context.approvedProposal() != null) {
            return context.approvedProposal().approvalId();
        }
        return context.turnId() + ":" + call.name() + ":" + ToolArgumentCanonicalizer.sha256(canonical);
    }

    private static boolean matchesApprovedProposal(ToolCallPreflight.NormalizedToolCall call,
                                                   ToolExecutionDescriptor descriptor,
                                                   ToolDispatchContext context) {
        var approved = context.approvedProposal();
        if (approved == null || descriptor == null) {
            return false;
        }
        try {
            String canonical = ToolArgumentCanonicalizer.canonicalize(call.arguments());
            return java.util.Objects.equals(approved.toolName(), call.name())
                    && java.util.Objects.equals(approved.canonicalArguments(), canonical)
                    && java.util.Objects.equals(approved.argumentHash(), ToolArgumentCanonicalizer.sha256(canonical))
                    && java.util.Objects.equals(approved.descriptorHash(), descriptor.stableHash())
                    && java.util.Objects.equals(approved.policyVersion(), context.policyVersion())
                    && java.util.Objects.equals(approved.contractVersion(), context.contractVersion());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static String sha256(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "sha256-unavailable";
        }
    }

    private static ToolResponseMessage.ToolResponse safeResponse(
            ToolCallPreflight.NormalizedToolCall call, String message) {
        return new ToolResponseMessage.ToolResponse(call.callId(), call.name(), message);
    }

    private static ToolResponseMessage.ToolResponse approvalResponse(
            ToolCallPreflight.NormalizedToolCall call, ToolApprovalChallenge challenge) {
        String body = challenge.isAcceptable()
                ? "CONFIRMATION_REQUIRED: approval " + challenge.hashPrefix()
                + " — confirm tool '" + call.name() + "' to proceed; preview=" + challenge.safePreview()
                : "CONFIRMATION_REJECTED: " + challenge.violationCode();
        return new ToolResponseMessage.ToolResponse(call.callId(), call.name(), body);
    }

    private static String violationMessage(ToolCallPreflight.NormalizedToolCall call) {
        String v = call.violation();
        return switch (v == null ? "" : v) {
            case "TOOL_NAME_MISSING" -> "Error: tool name is missing; provide a valid tool name.";
            case "TOOL_NAME_TOO_LONG" -> "Error: tool name is too long; provide a valid tool name.";
            case "TOOL_ARGUMENTS_TOO_LARGE" ->
                    "Error: tool arguments exceed the size limit; reduce the request payload.";
            default -> "Error: tool call rejected by preflight (" + v + ").";
        };
    }

    /**
     * 协调器层面的 per-step 上限兜底。ReAct 路径里 {@code AgentThinkingEngine} 已经用自己的
     * {@code maxToolCallsPerStep} 做了更紧的截断；这里给 DeepThink 直连路径一个保守的默认，
     * 避免没有任何上限。上层运行时仍可在调用协调器前自行截断。
     */
    static final int DEFAULT_MAX_CALLS_PER_STEP = 32;

    /** 把规范化 assistant 消息与其配对 tool 响应拼成新的 conversation history。 */
    private static List<org.springframework.ai.chat.messages.Message> appendPaired(
            Prompt prompt,
            AssistantMessage normalizedAssistant,
            ToolResponseMessage paired) {
        List<org.springframework.ai.chat.messages.Message> base =
                prompt.getInstructions() != null
                        ? new java.util.ArrayList<>(prompt.getInstructions())
                        : new java.util.ArrayList<>();
        // 用规范化后的 assistant 消息替换历史里最后一条（即原始未规范化的版本），
        // 保证后续模型看到的是 preflight 后的稳定 id / 有界参数。
        if (!base.isEmpty()) {
            base.remove(base.size() - 1);
        }
        base.add(normalizedAssistant);
        base.add(paired);
        return List.copyOf(base);
    }

    private static List<ToolCallback> sanitize(List<ToolCallback> availableTools) {
        if (availableTools == null || availableTools.isEmpty()) {
            return List.of();
        }
        return availableTools.stream()
                .filter(callback -> callback != null
                        && callback.getToolDefinition() != null
                        && StringUtils.hasText(callback.getToolDefinition().name()))
                .toList();
    }

    public record TerminalCommit(
            String executionKey,
            String toolCallId,
            String terminalState,
            String responseHash,
            String safeErrorCode,
            int expectedAttempt) {
    }

    public record CoordinatedExecution(
            ToolExecutionResult executionResult,
            ToolResponseMessage response,
            List<TerminalCommit> terminalCommits) {
    }

    private record DispatchBatch(
            ToolResponseMessage response,
            List<TerminalCommit> terminalCommits) {
    }

    private record DispatchOutcome(
            ToolResponseMessage.ToolResponse response,
            TerminalCommit terminalCommit) {

        static DispatchOutcome withoutCommit(ToolResponseMessage.ToolResponse response) {
            return new DispatchOutcome(response, null);
        }
    }
}
