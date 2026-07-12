package com.yulong.chatagent.chat.routing;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.zhipuai.ZhiPuAiAssistantMessage;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * 原始厂商 SSE 不可用时的兜底流式适配器。
 *
 * <p>ProviderDirectStreamSupport 失败或找不到 provider binding 时，会回退到这里。
 * 它使用 Spring AI 的 ChatClient.stream()，每个非空且已解析的 ChatResponse 先按传输事件
 * 触发 {@link StreamCallback#onSignal()}，再把正文、thinking 和工具调用转换成路由层事件。</p>
 */
public final class ReactiveStreamAdapter {

    private ReactiveStreamAdapter() {}

    public static Disposable submit(ChatClient client, Prompt prompt, StreamCallback callback) {
        // 这里拿到的是 Spring AI 已经解析好的 ChatResponse 流，而不是原始 data: ... 文本。
        Flux<ChatResponse> flux = client.prompt(prompt).stream().chatResponse();

        // 使用真正的响应式订阅，避免线程池挂死
        return flux.subscribe(
                response -> extractAndDispatch(response, callback),
                callback::onError,
                callback::onComplete
        );
    }

    static void extractAndDispatch(ChatResponse response, StreamCallback callback) {
        if (response == null) return;

        // 与原始 SSE 路径保持相同的 transport-first 语义：Spring AI 能交付一个
        // ChatResponse 就说明 provider 已产生有效非终止 chunk。即使它只有 role、
        // usage、metadata 或 empty choices，也必须先完成首包探测。
        callback.onSignal();

        Optional.ofNullable(response.getResult())
                .map(r -> r.getOutput())
                .ifPresent(output -> {
                    // 提取正文。这里的 content 已经由 Spring AI 从厂商 chunk 中解析出来。
                    String content = output.getText();
                    if (StringUtils.hasLength(content)) {
                        callback.onContent(content);
                    }
                    // ChatClient.stream() 已经把工具调用组织成 AssistantMessage.ToolCall，
                    // 不需要像原始 SSE 那样手动累积分片。
                    if (output.getToolCalls() != null && !output.getToolCalls().isEmpty()) {
                        callback.onToolCalls(output.getToolCalls());
                    }
                    // thinking 字段在不同 provider 的 Spring AI message 类型里位置不同，需要单独兼容。
                    extractReasoningContent(output)
                            .ifPresent(callback::onThinking);
                });
    }

    private static Optional<String> extractReasoningContent(AssistantMessage output) {
        String providerReasoning = null;
        // Spring AI 1.1.0 会把 provider chunk 的 delta.reasoning_content
        // 映射到这些 provider-specific AssistantMessage getter 中。
        if (output instanceof DeepSeekAssistantMessage deepSeekMessage) {
            providerReasoning = deepSeekMessage.getReasoningContent();
        } else if (output instanceof ZhiPuAiAssistantMessage zhiPuAiMessage) {
            providerReasoning = zhiPuAiMessage.getReasoningContent();
        }
        if (StringUtils.hasLength(providerReasoning)) {
            return Optional.of(providerReasoning);
        }

        // 兜底从 metadata 查找，兼容不同 provider 或 Spring AI 版本的字段命名。
        return Optional.ofNullable(output.getMetadata())
                .map(metadata -> {
                    Object snakeCase = metadata.get("reasoning_content");
                    if (snakeCase != null) {
                        return snakeCase;
                    }
                    Object camelCase = metadata.get("reasoningContent");
                    if (camelCase != null) {
                        return camelCase;
                    }
                    return metadata.get("reasoning");
                })
                .map(Object::toString)
                .filter(StringUtils::hasLength);
    }
}
