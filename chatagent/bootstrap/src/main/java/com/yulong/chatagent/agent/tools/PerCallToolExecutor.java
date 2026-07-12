package com.yulong.chatagent.agent.tools;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ARRB Phase 1：把模型一批 tool call 拆成 per-call 执行，保证一个回调失败不会
 * 终止整批（ARRB-AC-005/006）。
 * <p>
 * 现有 {@link ToolCallingManager} 在任一回调抛异常时把整批转成异常。这里改成按原始顺序
 * 逐个执行：每个 call 单独 try/catch，失败的 call 产出一条 typed safe 错误 observation，
 * 成功的 call 产出它真实的工具响应；最终组装成一条有序的、每个 retained call 都有配对
 * 响应的 {@link ToolResponseMessage}。
 * <p>
 * 该执行器不持有状态、不做预算/重复/确认判定（那是 coordinator 的职责），它只负责
 * per-call 隔离 + 配对。它复用 Spring AI 的 {@code ToolCallingManager} 来执行成功路径，
 * 单独的失败隔离路径在 per-call 重试时启用。
 */
public final class PerCallToolExecutor {

    private PerCallToolExecutor() {
    }

    /**
     * 按原始顺序执行每个 retained tool call，逐个隔离异常，返回一条配对完整的 ToolResponseMessage。
     * <p>
     * 实现策略：对每个 call 单独构造一个只含该 call 的 AssistantMessage + ChatResponse，
     * 调用 {@code manager.executeToolCalls}；若该单 call 执行抛异常，则落一条 safe 错误响应，
     * 继续下一个 call。这样保证：兄弟 call 失败不抑制其它 call。
     *
     * @param manager       Spring AI ToolCallingManager（已绑定 callbacks）
     * @param preflightResult ToolCallPreflight 的规范化结果（calls 顺序与 assistantMessage 一致）
     * @param chatOptions   当前 chat options（携带 toolContext）
     * @param prompt        当前 prompt（提供 conversation history 上下文）
     * @return 配对完整的 ToolResponseMessage，顺序与 preflightResult.calls() 一致
     */
    public static ToolResponseMessage executePerCall(ToolCallingManager manager,
                                                     ToolCallPreflight.ToolCallPreflightResult preflightResult,
                                                     ChatOptions chatOptions,
                                                     Prompt prompt) {
        List<ToolCallPreflight.NormalizedToolCall> calls = preflightResult.calls();
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(calls.size());
        for (ToolCallPreflight.NormalizedToolCall call : calls) {
            responses.add(executeOne(manager, call, chatOptions, prompt));
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    private static ToolResponseMessage.ToolResponse executeOne(
            ToolCallingManager manager,
            ToolCallPreflight.NormalizedToolCall call,
            ChatOptions chatOptions,
            Prompt prompt) {
        // 有 preflight violation（missing name / 过大参数）的 call 不派发回调，
        // 直接落一条 typed safe 修正 observation。
        if (call.hasViolation()) {
            return safeErrorResponse(call, violationMessage(call));
        }
        try {
            AssistantMessage single = AssistantMessage.builder()
                    .toolCalls(List.of(call.toToolCall()))
                    .build();
            ChatResponse singleResponse = new ChatResponse(List.of(new Generation(single)));
            Prompt singlePrompt = Prompt.builder()
                    .messages(prompt != null && prompt.getInstructions() != null
                            ? prompt.getInstructions() : List.of(single))
                    .chatOptions(chatOptions)
                    .build();
            ToolExecutionResult result = manager.executeToolCalls(singlePrompt, singleResponse);
            List<org.springframework.ai.chat.messages.Message> history = result.conversationHistory();
            if (history != null && !history.isEmpty()
                    && history.get(history.size() - 1) instanceof ToolResponseMessage trm
                    && trm.getResponses() != null && !trm.getResponses().isEmpty()) {
                ToolResponseMessage.ToolResponse resp = trm.getResponses().get(0);
                // 保留 preflight 规范化后的 callId，避免被 Spring AI 改回原始 id。
                return new ToolResponseMessage.ToolResponse(call.callId(), resp.name(), resp.responseData());
            }
            return safeErrorResponse(call, "Tool execution produced no paired response");
        } catch (RuntimeException ex) {
            // per-call 隔离：一个回调异常不终止整批。落 typed safe 错误 observation。
            return safeErrorResponse(call, retryableException(ex)
                    ? "TOOL_RETRYABLE_FAILURE"
                    : "TOOL_CALLBACK_FAILURE");
        }
    }

    private static boolean retryableException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String name = current.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("timeout") || name.contains("resourceaccess")
                    || name.contains("connection") || name.contains("ioexception")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static ToolResponseMessage.ToolResponse safeErrorResponse(
            ToolCallPreflight.NormalizedToolCall call, String message) {
        return new ToolResponseMessage.ToolResponse(call.callId(), call.name(), message);
    }

    private static String violationMessage(ToolCallPreflight.NormalizedToolCall call) {
        // 修正提示：不回显原始参数、不枚举全局 catalog，只给有界的 typed 原因。
        String v = call.violation();
        return switch (v == null ? "" : v) {
            case "TOOL_NAME_MISSING" -> "Error: tool name is missing; provide a valid tool name.";
            case "TOOL_NAME_TOO_LONG" -> "Error: tool name is too long; provide a valid tool name.";
            case "TOOL_ARGUMENTS_TOO_LARGE" ->
                    "Error: tool arguments exceed the size limit; reduce the request payload.";
            default -> "Error: tool call rejected by preflight (" + v + ").";
        };
    }

    /** 用于测试：从一个 callback 列表按 name 建立查找映射。 */
    public static Map<String, ToolCallback> indexByName(List<ToolCallback> callbacks) {
        Map<String, ToolCallback> index = new HashMap<>();
        if (callbacks == null) {
            return index;
        }
        for (ToolCallback cb : callbacks) {
            if (cb != null && cb.getToolDefinition() != null && cb.getToolDefinition().name() != null) {
                index.putIfAbsent(cb.getToolDefinition().name(), cb);
            }
        }
        return index;
    }
}
