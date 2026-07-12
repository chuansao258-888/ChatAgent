package com.yulong.chatagent.agent.tools;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 在任何 ReAct/DeepThink assistant 工具调用消息被持久化或派发之前，对其进行规范化与边界的唯一共享预检。
 * <p>
 * 设计目标（ARRB Phase 1）：
 * <ul>
 *   <li>把批大小限制在既有 per-step 上限之内，避免内存加载器因为超大 batch 丢弃整组序列；</li>
 *   <li>校验 tool name / call id 长度，并用稳定的服务端 id 替换空白、重复或过长的模型 id，
 *       保证 DB 中 assistant tool_calls 与后续 tool_response 能严格配对；</li>
 *   <li>在解析之前就拒绝超过 {@value #MAX_ARGUMENT_BYTES} UTF-8 字节的原始参数，
 *       永不持久化溢出载荷——超限调用被替换成 {@code {}} 加一个带类型的 preflight 违规，
 *       由 coordinator 落成一条修正性 observation；</li>
 *   <li>不直接落库、不发布 SSE、不依赖 Spring；它只是一个可被 bridge 与 coordinator 复用的纯函数。</li>
 * </ul>
 * 它替换了原本散落在 ReAct {@code AgentThinkingEngine} 与 DeepThink step 执行器里、
 * 持久化前各自截断 tool calls 的重复分支（ARRB-CLN-001）。
 */
public final class ToolCallPreflight {

    /** 单个 tool call 原始参数的 UTF-8 字节上限；超过即被视为不可持久化的溢出。 */
    public static final int MAX_ARGUMENT_BYTES = 32_768;

    /** 单个 tool call 原始 name 的字符上限，用于拦截明显异常的模型输出。 */
    public static final int MAX_NAME_CHARS = 256;

    /** 单个 tool call id 的字符上限，用于拦截明显异常的模型输出。 */
    public static final int MAX_CALL_ID_CHARS = 512;

    private final int maxToolCallsPerStep;

    /**
     * @param maxToolCallsPerStep 每步允许的最大 tool call 数量；必须 &gt;= 1。
     */
    public ToolCallPreflight(int maxToolCallsPerStep) {
        if (maxToolCallsPerStep < 1) {
            throw new IllegalArgumentException("maxToolCallsPerStep must be >= 1");
        }
        this.maxToolCallsPerStep = maxToolCallsPerStep;
    }

    /**
     * 对一条 assistant 输出做预检，返回规范化后的 assistant 消息和每个 call 的规范化结果。
     * <p>
     * 该方法不抛异常：所有违规都被记录成 {@link NormalizedToolCall#violation()}，
     * 由 coordinator 决定如何把违规 observation 落给模型。
     *
     * @param assistantMessage 模型原始输出，必须非空；可为无 tool call 的普通 assistant 消息
     * @return 预检结果，永远非空；若输入没有 tool call，返回一个 empty 结果
     */
    public ToolCallPreflightResult normalize(AssistantMessage assistantMessage) {
        if (assistantMessage == null) {
            return ToolCallPreflightResult.empty();
        }
        List<AssistantMessage.ToolCall> rawCalls = assistantMessage.getToolCalls();
        if (rawCalls == null || rawCalls.isEmpty()) {
            return ToolCallPreflightResult.empty();
        }

        // 1) 先按 per-step 上限截断：超出的 call 永不进入规范化序列，coordinator 会据此
        //    发出一条有界的 TOOL_BATCH_TRUNCATED observation。
        boolean truncated = rawCalls.size() > maxToolCallsPerStep;
        List<AssistantMessage.ToolCall> retained =
                rawCalls.size() > maxToolCallsPerStep
                        ? List.copyOf(rawCalls.subList(0, maxToolCallsPerStep))
                        : rawCalls;

        // 2) 逐 call 规范化：稳定 id、name 校验、参数字节上限。
        //    用 set 检测模型自带的重复 id；遇到空白/重复/过长 id 时分配稳定的服务端 id。
        Set<String> seenServerIds = new HashSet<>();
        Set<String> modelIdsSeen = new HashSet<>();
        List<NormalizedToolCall> normalized = new ArrayList<>(retained.size());
        int serverIdSeq = 0;
        for (int i = 0; i < retained.size(); i++) {
            AssistantMessage.ToolCall call = retained.get(i);
            NormalizedToolCall nc = normalizeOne(call, i, serverIdSeq, modelIdsSeen);
            serverIdSeq = Math.max(serverIdSeq, nc.serverSequence() + 1);
            // 服务端 id 在同一 batch 内必须唯一，防止规范化后再次出现配对歧义。
            if (!seenServerIds.add(nc.callId())) {
                nc = nc.withCallId(deduplicatedServerId(nc.serverSequence(), seenServerIds));
                seenServerIds.add(nc.callId());
            }
            normalized.add(nc);
        }

        // 3) 用规范化后的 id/name/参数重建 assistant 消息，保证持久化的就是被边界检查过的版本。
        AssistantMessage rebuilt = AssistantMessage.builder()
                .content(assistantMessage.getText())
                .toolCalls(normalized.stream().map(NormalizedToolCall::toToolCall).toList())
                .build();
        return new ToolCallPreflightResult(rebuilt, List.copyOf(normalized), truncated);
    }

    private NormalizedToolCall normalizeOne(AssistantMessage.ToolCall call,
                                            int retainedIndex,
                                            int serverIdSeqStart,
                                            Set<String> modelIdsSeen) {
        String rawName = call == null ? null : call.name();
        String rawType = call == null ? null : call.type();
        String rawId = call == null ? null : call.id();
        String rawArguments = call == null ? null : call.arguments();

        // name 校验：空白或过长直接落成 INVALID_NAME 违规，name 仍保留（不截断），
        // 以便 coordinator 给出"最多列出本回合已可见 5 个 callback 名"的修正提示。
        String name = StringUtils.hasText(rawName) ? rawName.trim() : null;
        String nameViolation = null;
        if (name == null) {
            nameViolation = "TOOL_NAME_MISSING";
        } else if (name.length() > MAX_NAME_CHARS) {
            nameViolation = "TOOL_NAME_TOO_LONG";
        }

        // 参数字节上限：在解析之前就拦截，超限 call 的存储参数被替换成 {}，
        // coordinator 用 violation 把有界的修正 observation 落给模型，永不持久化原始溢出。
        String violation = nameViolation;
        String storedArguments = StringUtils.hasText(rawArguments) ? rawArguments : "{}";
        if (storedArguments.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) {
            violation = "TOOL_ARGUMENTS_TOO_LARGE";
            storedArguments = "{}";
        }

        // id 规范化：模型给的空白/重复/过长 id 一律替换成稳定的服务端 id。
        int serverSequence = serverIdSeqStart + retainedIndex;
        String callId = resolveStableId(rawId, modelIdsSeen, serverSequence);

        return new NormalizedToolCall(
                callId,
                serverSequence,
                name,
                rawType,
                storedArguments,
                violation,
                truncatedArgument(rawArguments, storedArguments));
    }

    private String resolveStableId(String rawId, Set<String> modelIdsSeen, int serverSequence) {
        if (StringUtils.hasText(rawId)
                && rawId.length() <= MAX_CALL_ID_CHARS
                && modelIdsSeen.add(rawId)) {
            return rawId;
        }
        return serverId(serverSequence);
    }

    private static String serverId(int serverSequence) {
        return "tc_" + serverSequence;
    }

    private static String deduplicatedServerId(int serverSequence, Set<String> seenServerIds) {
        String candidate = serverId(serverSequence);
        int suffix = 1;
        while (seenServerIds.contains(candidate)) {
            candidate = serverId(serverSequence) + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private static String truncatedArgument(String rawArguments, String storedArguments) {
        // 仅记录参数是否被截断这一事实，便于审计/测试；不保留原始溢出文本。
        if (rawArguments == null) {
            return null;
        }
        return storedArguments.equals(rawArguments) ? null : "ARGUMENTS_REPLACED";
    }

    /** 单个 tool call 的规范化结果。 */
    public record NormalizedToolCall(
            String callId,
            int serverSequence,
            String name,
            String type,
            String arguments,
            String violation,
            String argumentReplacementNote) {

        /**
         * 重建一个 Spring AI {@link AssistantMessage.ToolCall}，用规范化后的 id/name/参数。
         */
        public AssistantMessage.ToolCall toToolCall() {
            return new AssistantMessage.ToolCall(callId, type, name, arguments);
        }

        NormalizedToolCall withCallId(String newCallId) {
            return new NormalizedToolCall(
                    newCallId, serverSequence, name, type, arguments, violation, argumentReplacementNote);
        }

        public boolean hasViolation() {
            return violation != null;
        }
    }

    /**
     * 预检结果。
     *
     * @param assistantMessage 规范化后的 assistant 消息；empty 时为 null
     * @param calls 每个保留下来的 call 的规范化记录，顺序与 assistant message 一致
     * @param batchTruncated 是否因为超过 per-step 上限而被截断（被截断的 call 不在 calls 里）
     */
    public record ToolCallPreflightResult(
            AssistantMessage assistantMessage,
            List<NormalizedToolCall> calls,
            boolean batchTruncated) {

        public boolean isEmpty() {
            return calls == null || calls.isEmpty();
        }

        static ToolCallPreflightResult empty() {
            return new ToolCallPreflightResult(null, List.of(), false);
        }
    }
}
