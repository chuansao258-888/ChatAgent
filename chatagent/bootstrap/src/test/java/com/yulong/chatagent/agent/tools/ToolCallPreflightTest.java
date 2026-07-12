package com.yulong.chatagent.agent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖 {@link ToolCallPreflight} 的规范化与边界行为。这是 ARRB Phase 1 的 per-call 预检核心，
 * 保证 raw 的超限参数与异常 id 在任何持久化之前就被拦截。
 */
class ToolCallPreflightTest {

    private static AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    private static AssistantMessage withCalls(AssistantMessage.ToolCall... calls) {
        return AssistantMessage.builder()
                .content("thinking")
                .toolCalls(List.of(calls))
                .build();
    }

    @Test
    void emptyOrNullInputsProduceEmptyResultWithoutThrowing() {
        ToolCallPreflight preflight = new ToolCallPreflight(4);
        assertThat(preflight.normalize(null).isEmpty()).isTrue();
        assertThat(preflight.normalize(AssistantMessage.builder().content("no tools").build()).isEmpty()).isTrue();
        assertThat(preflight.normalize(AssistantMessage.builder().build()).isEmpty()).isTrue();
    }

    @Test
    void rejectsInvalidMaxToolCallsPerStep() {
        assertThatThrownBy(() -> new ToolCallPreflight(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolCallPreflight(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void truncatesBatchToPerStepCapAndFlagsTruncation() {
        // 超出 per-step 上限的 call 永不进入规范化序列：coordinator 会据此发一条有界 observation。
        ToolCallPreflight preflight = new ToolCallPreflight(2);
        AssistantMessage msg = withCalls(
                call("id-1", "webSearch", "{}"),
                call("id-2", "dataBaseTool", "{}"),
                call("id-3", "webSearch", "{}"),
                call("id-4", "dataBaseTool", "{}"));

        ToolCallPreflight.ToolCallPreflightResult result = preflight.normalize(msg);

        assertThat(result.batchTruncated()).isTrue();
        assertThat(result.calls()).hasSize(2);
        // 保留的是前两个（原始顺序）。
        assertThat(result.calls().get(0).name()).isEqualTo("webSearch");
        assertThat(result.calls().get(1).name()).isEqualTo("dataBaseTool");
        // 重建的 assistant 消息只包含保留下来的两个 call。
        assertThat(result.assistantMessage().getToolCalls()).hasSize(2);
        assertThat(result.assistantMessage().getText()).isEqualTo("thinking");
    }

    @Test
    void doesNotFlagTruncationWhenWithinCap() {
        ToolCallPreflight preflight = new ToolCallPreflight(4);
        AssistantMessage msg = withCalls(
                call("id-1", "webSearch", "{}"),
                call("id-2", "dataBaseTool", "{}"));

        ToolCallPreflight.ToolCallPreflightResult result = preflight.normalize(msg);

        assertThat(result.batchTruncated()).isFalse();
        assertThat(result.calls()).hasSize(2);
    }

    @Test
    void replacesBlankDuplicateAndOversizeModelIdsWithStableServerIds() {
        ToolCallPreflight preflight = new ToolCallPreflight(4);
        // 空白 id、重复 id、过长 id 都必须被替换成稳定且唯一的服务端 id。
        String oversize = "x".repeat(ToolCallPreflight.MAX_CALL_ID_CHARS + 1);
        AssistantMessage msg = withCalls(
                call("", "webSearch", "{}"),
                call("dup", "dataBaseTool", "{}"),
                call("dup", "webSearch", "{}"),
                call(oversize, "dataBaseTool", "{}"));

        ToolCallPreflight.ToolCallPreflightResult result = preflight.normalize(msg);

        assertThat(result.calls()).hasSize(4);
        List<String> ids = result.calls().stream().map(ToolCallPreflight.NormalizedToolCall::callId).toList();
        // 全部唯一、非空。
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).allMatch(s -> !s.isBlank());
        // 空白 id 和过长 id 都被替换（不再是 "" 或 oversize）。
        assertThat(ids).doesNotContain("", oversize);
        // 第一个 call 的空白 id 被替换成稳定服务端 id。
        assertThat(ids.get(0)).startsWith("tc_");
        // 第一个出现的有效模型 id "dup" 保留，随后重复的 "dup" 被替换成服务端 id。
        assertThat(ids.get(1)).isEqualTo("dup");
        assertThat(ids.get(2)).startsWith("tc_");
        // 过长 id 被替换，不再是 oversize。
        assertThat(ids.get(3)).isNotEqualTo(oversize).startsWith("tc_");
    }

    @Test
    void rejectsOversizeArgumentsBeforePersistenceAndReplacesWithEmptyObject() {
        // 在解析之前就拒绝超过 MAX_ARGUMENT_BYTES 的原始参数：存储值变成 {}，并打 violation。
        ToolCallPreflight preflight = new ToolCallPreflight(4);
        String oversizeArgs = "{\"q\":\"" + "a".repeat(ToolCallPreflight.MAX_ARGUMENT_BYTES + 1) + "\"}";
        // 确认测试夹具确实超限。
        assertThat(oversizeArgs.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(ToolCallPreflight.MAX_ARGUMENT_BYTES);

        AssistantMessage msg = withCalls(call("id-1", "webSearch", oversizeArgs));
        ToolCallPreflight.ToolCallPreflightResult result = preflight.normalize(msg);

        assertThat(result.calls()).hasSize(1);
        ToolCallPreflight.NormalizedToolCall nc = result.calls().get(0);
        assertThat(nc.violation()).isEqualTo("TOOL_ARGUMENTS_TOO_LARGE");
        // 永不持久化原始溢出：重建消息里的参数被替换成 {}。
        assertThat(nc.arguments()).isEqualTo("{}");
        assertThat(result.assistantMessage().getToolCalls().get(0).arguments()).isEqualTo("{}");
        // 仅记录"参数被替换"这一事实，不保留原始溢出文本。
        assertThat(nc.argumentReplacementNote()).isEqualTo("ARGUMENTS_REPLACED");
    }

    @Test
    void flagsMissingAndOversizeNamesWithoutDroppingTheCall() {
        // name 违规不截断 name（coordinator 据此给修正提示），但需要落 violation。
        ToolCallPreflight preflight = new ToolCallPreflight(4);
        String oversizeName = "n".repeat(ToolCallPreflight.MAX_NAME_CHARS + 1);
        AssistantMessage msg = withCalls(
                call("id-1", "  ", "{}"),
                call("id-2", oversizeName, "{}"),
                call("id-3", "webSearch", "{}"));

        ToolCallPreflight.ToolCallPreflightResult result = preflight.normalize(msg);

        assertThat(result.calls()).hasSize(3);
        assertThat(result.calls().get(0).violation()).isEqualTo("TOOL_NAME_MISSING");
        assertThat(result.calls().get(1).violation()).isEqualTo("TOOL_NAME_TOO_LONG");
        assertThat(result.calls().get(2).violation()).isNull();
    }

    @Test
    void keepsValidCallsUnchanged() {
        ToolCallPreflight preflight = new ToolCallPreflight(4);
        AssistantMessage msg = withCalls(call("call-9", "webSearch", "{\"q\":\"cats\"}"));

        ToolCallPreflight.ToolCallPreflightResult result = preflight.normalize(msg);

        assertThat(result.calls()).hasSize(1);
        ToolCallPreflight.NormalizedToolCall nc = result.calls().get(0);
        assertThat(nc.callId()).isEqualTo("call-9");
        assertThat(nc.name()).isEqualTo("webSearch");
        assertThat(nc.arguments()).isEqualTo("{\"q\":\"cats\"}");
        assertThat(nc.violation()).isNull();
        assertThat(nc.hasViolation()).isFalse();
        assertThat(result.assistantMessage().getToolCalls().get(0).id()).isEqualTo("call-9");
    }
}
