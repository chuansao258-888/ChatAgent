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

public final class ReactiveStreamAdapter {

    private ReactiveStreamAdapter() {}

    public static Disposable submit(ChatClient client, Prompt prompt, StreamCallback callback) {
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
        
        Optional.ofNullable(response.getResult())
                .map(r -> r.getOutput())
                .ifPresent(output -> {
                    // 提取正文
                    String content = output.getText();
                    if (StringUtils.hasLength(content)) {
                        callback.onContent(content);
                    }
                    if (output.getToolCalls() != null && !output.getToolCalls().isEmpty()) {
                        callback.onToolCalls(output.getToolCalls());
                    }
                    extractReasoningContent(output)
                            .ifPresent(callback::onThinking);
                });
    }

    private static Optional<String> extractReasoningContent(AssistantMessage output) {
        String providerReasoning = null;
        // Spring AI 1.1.0 already maps provider chunk delta.reasoning_content
        // into these provider-specific AssistantMessage getters during stream().
        if (output instanceof DeepSeekAssistantMessage deepSeekMessage) {
            providerReasoning = deepSeekMessage.getReasoningContent();
        } else if (output instanceof ZhiPuAiAssistantMessage zhiPuAiMessage) {
            providerReasoning = zhiPuAiMessage.getReasoningContent();
        }
        if (StringUtils.hasLength(providerReasoning)) {
            return Optional.of(providerReasoning);
        }

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
