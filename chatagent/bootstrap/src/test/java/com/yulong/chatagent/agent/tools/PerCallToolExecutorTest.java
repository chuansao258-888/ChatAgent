package com.yulong.chatagent.agent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 覆盖 {@link PerCallToolExecutor} 的 per-call 隔离与配对语义（ARRB-AC-005/006 核心）。
 * <p>
 * 关键不变量：一个回调失败不终止整批；preflight violation 的 call 不派发、落 safe 修正 observation；
 * 每个 retained call 都有恰好一条配对响应，顺序与 preflight 一致。
 */
class PerCallToolExecutorTest {

    private static AssistantMessage.ToolCall call(String id, String name, String args) {
        return new AssistantMessage.ToolCall(id, "function", name, args);
    }

    private static ToolCallPreflight.NormalizedToolCall nc(String id, String name, String args, String violation) {
        return new ToolCallPreflight.NormalizedToolCall(id, 0, name, "function", args, violation, null);
    }

    private ToolCallPreflight.ToolCallPreflightResult result(ToolCallPreflight.NormalizedToolCall... calls) {
        return new ToolCallPreflight.ToolCallPreflightResult(null, List.of(calls), false);
    }

    @Test
    void siblingFailureDoesNotAbortBatch() {
        // 第一个 call 成功，第二个抛异常，第三个成功：三个都要有配对响应，顺序不变。
        ToolCallingManager manager = mock(ToolCallingManager.class);
        when(manager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenAnswer(inv -> {
                    Prompt p = inv.getArgument(0);
                    AssistantMessage am = (AssistantMessage) p.getInstructions().get(p.getInstructions().size() - 1);
                    String id = am.getToolCalls().get(0).id();
                    if ("id-2".equals(id)) {
                        throw new RuntimeException("callback boom");
                    }
                    return toolResultFor(id, am.getToolCalls().get(0).name(), "ok-" + id);
                });

        ToolCallPreflight.ToolCallPreflightResult preflight = result(
                nc("id-1", "webSearch", "{}", null),
                nc("id-2", "dataBaseTool", "{}", null),
                nc("id-3", "webSearch", "{}", null));

        ToolResponseMessage trm = PerCallToolExecutor.executePerCall(manager, preflight, null, null);

        assertThat(trm.getResponses()).hasSize(3);
        assertThat(trm.getResponses().get(0).responseData()).isEqualTo("ok-id-1");
        // 失败的 call 落 typed safe 错误，而不是终止整批。
        assertThat(trm.getResponses().get(1).responseData()).isEqualTo("TOOL_CALLBACK_FAILURE");
        assertThat(trm.getResponses().get(2).responseData()).isEqualTo("ok-id-3");
    }

    @Test
    void preflightViolationCallIsNotDispatchedAndGetsSafeResponse() {
        ToolCallingManager manager = mock(ToolCallingManager.class);
        // 有 violation 的 call 不应触发 manager.executeToolCalls，因此 mock 不需要 thenReturn。

        ToolCallPreflight.ToolCallPreflightResult preflight = result(
                nc("id-1", null, "{}", "TOOL_NAME_MISSING"),
                nc("id-2", "webSearch", "{}", "TOOL_ARGUMENTS_TOO_LARGE"));

        ToolResponseMessage trm = PerCallToolExecutor.executePerCall(manager, preflight, null, null);

        assertThat(trm.getResponses()).hasSize(2);
        assertThat(trm.getResponses().get(0).responseData()).contains("tool name is missing");
        assertThat(trm.getResponses().get(1).responseData()).contains("size limit");
        // 没有任何 manager 调用（violation call 不派发）。
        org.mockito.Mockito.verifyNoInteractions(manager);
    }

    @Test
    void emptyPreflightProducesEmptyResponseMessage() {
        ToolCallingManager manager = mock(ToolCallingManager.class);
        ToolCallPreflight.ToolCallPreflightResult empty =
                new ToolCallPreflight.ToolCallPreflightResult(null, List.of(), false);

        ToolResponseMessage trm = PerCallToolExecutor.executePerCall(manager, empty, null, null);

        assertThat(trm.getResponses()).isEmpty();
    }

    @Test
    void normalizedCallIdIsPreservedOnResponse() {
        // Spring AI 可能改写 call id；执行器应保留 preflight 规范化后的稳定 id。
        ToolCallingManager manager = mock(ToolCallingManager.class);
        when(manager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenAnswer(inv -> toolResultFor("anything", "webSearch", "result"));

        ToolCallPreflight.ToolCallPreflightResult preflight = result(nc("tc_0", "webSearch", "{}", null));

        ToolResponseMessage trm = PerCallToolExecutor.executePerCall(manager, preflight, null, null);

        assertThat(trm.getResponses()).hasSize(1);
        assertThat(trm.getResponses().get(0).id()).isEqualTo("tc_0");
    }

    /** 构造一个 manager 返回值：conversationHistory 末尾是带单条 response 的 ToolResponseMessage。 */
    private static org.springframework.ai.model.tool.ToolExecutionResult toolResultFor(
            String id, String name, String body) {
        ToolResponseMessage trm = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, body)))
                .build();
        return () -> List.of(trm);
    }
}
