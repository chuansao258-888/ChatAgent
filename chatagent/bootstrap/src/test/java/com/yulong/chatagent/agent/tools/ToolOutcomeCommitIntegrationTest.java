package com.yulong.chatagent.agent.tools;

import com.yulong.chatagent.agent.AgentToolExecutionCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARRB Phase 1（ARRB-AC-005）：端到端验证协调器对一批 tool call 产出的"配对完整结果"。
 * <p>
 * 关键不变量：每个 retained call 都恰好有一条配对响应；一个回调失败不抑制兄弟 call；
 * 重建后的 conversation history 末尾是一条 {@link ToolResponseMessage}，数量与 call 一致。
 */
class ToolOutcomeCommitIntegrationTest {

    @Test
    void everyRetainedCallGetsExactlyOnePairedResponseEvenWhenASiblingFails() {
        // 三个 call：第一个成功，第二个抛异常，第三个成功。
        ToolCallback okA = stubCallback("okA", "okA-result");
        ToolCallback boom = stubCallback("throwingTool", null) ;  // null result => throw
        ToolCallback okB = stubCallback("okB", "okB-result");

        AgentToolExecutionCoordinator coordinator = new AgentToolExecutionCoordinator(List.of(okA, boom, okB));

        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", "okA", "{}"),
                        new AssistantMessage.ToolCall("c2", "function", "throwingTool", "{}"),
                        new AssistantMessage.ToolCall("c3", "function", "okB", "{}")))
                .build();

        Prompt prompt = Prompt.builder()
                .messages(List.of(new org.springframework.ai.chat.messages.UserMessage("run"), assistant))
                .chatOptions(DefaultToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build())
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(assistant)));

        org.springframework.ai.model.tool.ToolExecutionResult result = coordinator.execute(prompt, response);

        // 末尾必须是配对的 ToolResponseMessage。
        org.springframework.ai.chat.messages.Message last =
                result.conversationHistory().get(result.conversationHistory().size() - 1);
        assertThat(last).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage trm = (ToolResponseMessage) last;

        // 每个 retained call 恰好一条配对响应，顺序与原始一致。
        assertThat(trm.getResponses()).hasSize(3);
        assertThat(trm.getResponses().get(0).responseData()).isEqualTo("okA-result");
        // 失败 call 产 safe 错误 observation，不抛、不抑制后续。
        assertThat(trm.getResponses().get(1).responseData()).isEqualTo("TOOL_CALLBACK_FAILURE");
        assertThat(trm.getResponses().get(2).responseData()).isEqualTo("okB-result");
    }

    @Test
    void preflightViolationCallEmitsBoundedCorrectiveResponseWithoutDispatch() {
        // 参数超 32,768 字节：preflight 拒绝、不派发回调、落 safe observation。
        ToolCallback ok = stubCallback("ok", "ok-result");
        AgentToolExecutionCoordinator coordinator = new AgentToolExecutionCoordinator(List.of(ok));

        String oversize = "{\"q\":\"" + "a".repeat(33_000) + "\"}";
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "ok", oversize)))
                .build();
        Prompt prompt = Prompt.builder()
                .messages(List.of(new org.springframework.ai.chat.messages.UserMessage("run"), assistant))
                .chatOptions(DefaultToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build())
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(assistant)));

        org.springframework.ai.model.tool.ToolExecutionResult result = coordinator.execute(prompt, response);
        ToolResponseMessage trm = (ToolResponseMessage) result.conversationHistory()
                .get(result.conversationHistory().size() - 1);

        assertThat(trm.getResponses()).hasSize(1);
        assertThat(trm.getResponses().get(0).responseData()).contains("size limit");
    }

    /** Stub callback: returns result, or throws if result is null (simulating a failing callback). */
    private static ToolCallback stubCallback(String name, String result) {
        ToolDefinition def = ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return def;
            }

            @Override
            public String call(String toolInput) {
                if (result == null) {
                    throw new RuntimeException("boom");
                }
                return result;
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return call(toolInput);
            }
        };
    }
}
