package com.yulong.chatagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.runtime.AgentRunBudget;
import com.yulong.chatagent.agent.runtime.AgentRunPolicy;
import com.yulong.chatagent.agent.tools.DescribedToolCallback;
import com.yulong.chatagent.agent.tools.ToolApprovalChallenge;
import com.yulong.chatagent.agent.tools.ToolApprovalChallengeBuilder;
import com.yulong.chatagent.agent.tools.ToolApprovalPort;
import com.yulong.chatagent.agent.tools.ToolDispatchContext;
import com.yulong.chatagent.agent.tools.ToolExecutionDescriptorResolver;
import com.yulong.chatagent.agent.tools.ToolExecutionLedgerPort;
import com.yulong.chatagent.agent.tools.ToolEffectClass;
import com.yulong.chatagent.agent.tools.ToolExecutionDescriptor;
import com.yulong.chatagent.agent.tools.DeadlineMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARRB Phase 1（cross-review F-1/F-2/F-4/F-5）：验证 coordinator 在注入 ToolDispatchContext 后的
 * 运行时级安全行为：非只读调用首次提案不派发（F-1）、ledger 每个 dispatch 写行 + 终态提交（F-2）、
 * 已成功指纹不重复派发（F-4）、预算耗尽时停止派发（F-5）。
 */
class AgentToolExecutionCoordinatorDispatchTest {

    @Test
    void nonReadOnlyCallIsNotDispatchedAndStagesApprovalChallenge() {
        // F-1（AC-008）：一个 NON_IDEMPOTENT/UNKNOWN 调用首次提案时 coordinator 不派发回调，
        // 而是返回 CONFIRMATION_REQUIRED 并 stage 一条 approval challenge。
        AtomicInteger dispatchCount = new AtomicInteger();
        ToolCallback nonReadOnly = describedCallback("sendEmail", "should-not-happen", dispatchCount,
                ToolEffectClass.NON_IDEMPOTENT);
        RecordingApprovalPort approval = new RecordingApprovalPort();

        AgentToolExecutionCoordinator coordinator = new AgentToolExecutionCoordinator(List.of(nonReadOnly), 4);
        ToolExecutionDescriptorResolver descriptors = new ToolExecutionDescriptorResolver(List.of(nonReadOnly));
        ToolDispatchContext ctx = new ToolDispatchContext(
                "session-1", "turn-1", approval, null, null, descriptors);

        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "sendEmail", "{}")))
                .build();
        Prompt prompt = Prompt.builder()
                .messages(List.of(assistant))
                .chatOptions(DefaultToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build())
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(assistant)));

        AgentToolExecutionCoordinator.CoordinatedExecution coordinated =
                coordinator.executeCoordinated(prompt, response, ctx);
        org.springframework.ai.model.tool.ToolExecutionResult result = coordinated.executionResult();
        ToolResponseMessage trm = (ToolResponseMessage) result.conversationHistory()
                .get(result.conversationHistory().size() - 1);

        // 回调从未被派发。
        assertThat(dispatchCount.get()).isZero();
        // 配对响应是 CONFIRMATION_REQUIRED，不含"should-not-happen"。
        assertThat(trm.getResponses()).hasSize(1);
        assertThat(trm.getResponses().get(0).responseData()).contains("CONFIRMATION_REQUIRED");
        assertThat(trm.getResponses().get(0).responseData()).doesNotContain("should-not-happen");
        // approval challenge 已 stage。
        assertThat(approval.stagedCount).isEqualTo(1);
    }

    @Test
    void readOnlyCallIsDispatchedAndLedgerCommitsTerminalState() {
        // F-2（AC-009）：只读调用被派发，ledger 写 PREPARED → DISPATCHING → SUCCEEDED 终态。
        AtomicInteger dispatchCount = new AtomicInteger();
        ToolCallback readOnly = describedCallback("webSearch", "search-result", dispatchCount,
                ToolEffectClass.READ_ONLY);
        RecordingApprovalPort approval = new RecordingApprovalPort();
        RecordingLedgerPort ledger = new RecordingLedgerPort();

        AgentToolExecutionCoordinator coordinator = new AgentToolExecutionCoordinator(List.of(readOnly), 4);
        ToolExecutionDescriptorResolver descriptors = new ToolExecutionDescriptorResolver(List.of(readOnly));
        ToolDispatchContext ctx = new ToolDispatchContext(
                "session-1", "turn-1", approval, ledger, null, descriptors);

        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "webSearch", "{\"q\":\"cats\"}")))
                .build();
        Prompt prompt = Prompt.builder()
                .messages(List.of(assistant))
                .chatOptions(DefaultToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build())
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(assistant)));

        AgentToolExecutionCoordinator.CoordinatedExecution coordinated =
                coordinator.executeCoordinated(prompt, response, ctx);
        org.springframework.ai.model.tool.ToolExecutionResult result = coordinated.executionResult();
        ToolResponseMessage trm = (ToolResponseMessage) result.conversationHistory()
                .get(result.conversationHistory().size() - 1);

        // 只读调用被派发了一次，结果落回模型。
        assertThat(dispatchCount.get()).isEqualTo(1);
        assertThat(trm.getResponses().get(0).responseData()).isEqualTo("search-result");
        // coordinator 只 prepare + tryDispatch；terminal commit 交给消息事务边界。
        assertThat(ledger.prepareCount).isEqualTo(1);
        assertThat(ledger.tryDispatchCount).isEqualTo(1);
        assertThat(ledger.commitTerminalCount).isZero();
        assertThat(coordinated.terminalCommits()).singleElement()
                .satisfies(commit -> assertThat(commit.terminalState()).isEqualTo("SUCCEEDED"));
    }

    @Test
    void coordinatedExecutionPreservesTrailingUserRequestBeforePairedToolMessages() {
        AtomicInteger dispatchCount = new AtomicInteger();
        ToolCallback readOnly = describedCallback("webSearch", "search-result", dispatchCount,
                ToolEffectClass.READ_ONLY);
        RecordingLedgerPort ledger = new RecordingLedgerPort();
        AgentToolExecutionCoordinator coordinator = new AgentToolExecutionCoordinator(List.of(readOnly), 4);
        ToolExecutionDescriptorResolver descriptors = new ToolExecutionDescriptorResolver(List.of(readOnly));
        ToolDispatchContext ctx = new ToolDispatchContext(
                "session-1", "turn-1", null, ledger, null, descriptors);
        UserMessage user = new UserMessage("Find the current Singapore time");
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "c1", "function", "webSearch", "{\"q\":\"Singapore time\"}")))
                .build();
        Prompt prompt = Prompt.builder()
                .messages(List.of(user))
                .chatOptions(DefaultToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false).build())
                .build();

        List<org.springframework.ai.chat.messages.Message> history = coordinator
                .executeCoordinated(prompt, new ChatResponse(List.of(new Generation(assistant))), ctx)
                .executionResult().conversationHistory();

        assertThat(history).hasSize(3);
        assertThat(history.get(0)).isSameAs(user);
        assertThat(history.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(history.get(2)).isInstanceOf(ToolResponseMessage.class);
    }

    @Test
    void succeededFingerprintIsNotRedispatched() {
        // F-4（AC-006）：同一个 execution key 已 SUCCEEDED 时，再次提案不重复派发。
        AtomicInteger dispatchCount = new AtomicInteger();
        ToolCallback readOnly = describedCallback("webSearch", "result", dispatchCount,
                ToolEffectClass.READ_ONLY);
        RecordingLedgerPort ledger = new RecordingLedgerPort();
        // 预置一条 SUCCEEDED 行，模拟上一轮已成功派发。
        ledger.preloadSucceeded("dispatch:v1:" + sha256(
                "turn-1\0webSearch\0" + sha256("{\"q\":\"cats\"}")));

        AgentToolExecutionCoordinator coordinator = new AgentToolExecutionCoordinator(List.of(readOnly), 4);
        ToolExecutionDescriptorResolver descriptors = new ToolExecutionDescriptorResolver(List.of(readOnly));
        ToolDispatchContext ctx = new ToolDispatchContext(
                "session-1", "turn-1", null, ledger, null, descriptors);

        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "webSearch", "{\"q\":\"cats\"}")))
                .build();
        Prompt prompt = Prompt.builder()
                .messages(List.of(assistant))
                .chatOptions(DefaultToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build())
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(assistant)));

        coordinator.execute(prompt, response, ctx);

        // 回调从未被再次派发（ALREADY_COMPLETED）。
        assertThat(dispatchCount.get()).isZero();
    }

    @Test
    void longMcpToolNameProducesBoundedStableExecutionKey() {
        AtomicInteger dispatchCount = new AtomicInteger();
        String longName = "mcp_" + "weather_timezone_converter_".repeat(8);
        ToolCallback readOnly = describedCallback(longName, "result", dispatchCount,
                ToolEffectClass.READ_ONLY);
        RecordingLedgerPort ledger = new RecordingLedgerPort();
        AgentToolExecutionCoordinator coordinator = new AgentToolExecutionCoordinator(List.of(readOnly), 4);
        ToolExecutionDescriptorResolver descriptors = new ToolExecutionDescriptorResolver(List.of(readOnly));
        ToolDispatchContext ctx = new ToolDispatchContext(
                "session-1", "2658f4e4-97cc-4bcd-aebf-8c4a2284ea82", null, ledger, null, descriptors);
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", longName, "{}")))
                .build();
        Prompt prompt = Prompt.builder().messages(List.of(assistant))
                .chatOptions(DefaultToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false).build())
                .build();

        coordinator.executeCoordinated(prompt,
                new ChatResponse(List.of(new Generation(assistant))), ctx);

        assertThat(ledger.lastPreparedKey).startsWith("dispatch:v1:").hasSize(76);
        assertThat(ledger.lastPreparedKey.length()).isLessThanOrEqualTo(128);
    }

    @Test
    void toolProposalBudgetExhaustionStopsDispatch() {
        // F-5（AC-007）：proposal 预算耗尽后，新提案落 REPEATED_CALL_BLOCKED 不派发。
        AtomicInteger dispatchCount = new AtomicInteger();
        ToolCallback readOnly = describedCallback("webSearch", "result", dispatchCount,
                ToolEffectClass.READ_ONLY);
        // 一个已经耗尽 proposal 预算的 budget。
        AgentRunPolicy policy = AgentRunPolicy.react(new com.yulong.chatagent.agent.runtime.AgentRunPolicyProperties());
        AgentRunBudget budget = new AgentRunBudget(policyWithTinyBudget(), System.nanoTime());
        // 消耗掉唯一的 proposal 配额
        assertThat(budget.consumeToolProposal()).isTrue(); // now exhausted

        AgentToolExecutionCoordinator coordinator = new AgentToolExecutionCoordinator(List.of(readOnly), 4);
        ToolExecutionDescriptorResolver descriptors = new ToolExecutionDescriptorResolver(List.of(readOnly));
        ToolDispatchContext ctx = new ToolDispatchContext("session-1", "turn-1", null, null, budget, descriptors);

        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "webSearch", "{}")))
                .build();
        Prompt prompt = Prompt.builder()
                .messages(List.of(assistant))
                .chatOptions(DefaultToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build())
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(assistant)));

        org.springframework.ai.model.tool.ToolExecutionResult result = coordinator.execute(prompt, response, ctx);
        ToolResponseMessage trm = (ToolResponseMessage) result.conversationHistory()
                .get(result.conversationHistory().size() - 1);

        assertThat(dispatchCount.get()).isZero();
        assertThat(trm.getResponses().get(0).responseData())
                .isEqualTo("TOOL_PROPOSAL_BUDGET_EXHAUSTED");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static ToolCallback stubCallback(String name, String result, AtomicInteger dispatchCount, boolean readOnly) {
        ToolDefinition def = ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return def;
            }

            @Override
            public String call(String toolInput) {
                dispatchCount.incrementAndGet();
                return result;
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return call(toolInput);
            }
        };
    }

    private static ToolCallback describedCallback(String name, String result,
                                                   AtomicInteger dispatchCount,
                                                   ToolEffectClass effectClass) {
        ToolCallback delegate = stubCallback(name, result, dispatchCount, effectClass == ToolEffectClass.READ_ONLY);
        return new DescribedToolCallback(delegate, new ToolExecutionDescriptor(
                name, effectClass, DeadlineMode.ENFORCED, false, 0));
    }

    static class RecordingApprovalPort implements ToolApprovalPort {
        int stagedCount;

        @Override
        public ToolApprovalChallenge stageProposal(ToolApprovalRequest request) {
            stagedCount++;
            return new ToolApprovalChallenge("appr-1", request.toolName(), request.rawArguments(),
                    sha256(request.rawArguments()), "{}", null);
        }
    }

    private static AgentRunPolicy policyWithTinyBudget() {
        return new AgentRunPolicy(1, 1, 0, 0, 1, 1, 0, 0,
                true, false, true, true, 1, 60_000L);
    }

    private static String sha256(String text) {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(d.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "x";
        }
    }

    /** 记录 ledger 调用次数与终态的测试替身。 */
    static class RecordingLedgerPort implements ToolExecutionLedgerPort {
        int prepareCount;
        int tryDispatchCount;
        int commitTerminalCount;
        String lastTerminalState;
        String lastPreparedKey;
        private final Map<String, JournalEntry> rows = new HashMap<>();

        @Override
        public Optional<JournalEntry> prepare(JournalEntry entry) {
            prepareCount++;
            lastPreparedKey = entry.executionKey();
            if (rows.containsKey(entry.executionKey())) {
                return Optional.empty();
            }
            rows.put(entry.executionKey(), entry);
            return Optional.of(entry);
        }

        @Override
        public boolean tryDispatch(String executionKey, String expectedState, int attempt, LocalDateTime dispatchedAt) {
            tryDispatchCount++;
            return true;
        }

        @Override
        public boolean commitTerminal(String executionKey, String expectedState, TerminalUpdate update) {
            commitTerminalCount++;
            lastTerminalState = update.newState();
            JournalEntry e = rows.get(executionKey);
            if (e != null) {
                rows.put(executionKey, new JournalEntry(
                        e.id(), e.executionKey(), e.sessionId(), e.turnId(), e.approvalId(),
                        e.assistantMessageId(), e.toolCallId(), e.toolName(), e.argumentHash(),
                        e.effectClass(), e.attempt(), update.newState(), update.safeErrorCode(),
                        update.responseMessageId(), update.responseHash(), e.dispatchedAt(),
                        e.callDeadlineMs(), e.createdAt(), LocalDateTime.now()));
            }
            return true;
        }

        @Override
        public Optional<JournalEntry> findByExecutionKey(String executionKey) {
            return Optional.ofNullable(rows.get(executionKey));
        }

        void preloadSucceeded(String executionKey) {
            rows.put(executionKey, new JournalEntry(
                    "id", executionKey, "session-1", "turn-1", null, null, "c1", "webSearch",
                    "h", "READ_ONLY", 1, "SUCCEEDED", null, null, "rh", null, null, null, null));
        }
    }
}
