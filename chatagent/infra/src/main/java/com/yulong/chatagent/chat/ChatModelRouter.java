package com.yulong.chatagent.chat;

import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
/**
 * Resolves which named {@link ChatClient} should serve a chat request.
 *
 * <p>When no model is requested explicitly, the router falls back to the
 * configured default model.</p>
 *
 * <p>中文说明：这是较早的“按 requestedModel 找 ChatClient”的简单路由器。
 * 首包探测主链路使用 routing 包里的 ModelSelector/RoutingLLMService；
 * 这里仍用于其他直接按模型名取 ChatClient 的调用场景。</p>
 */
public class ChatModelRouter {

    private final ChatClientRegistry chatClientRegistry;
    // 未显式请求模型时使用的默认 key，来自 chat.routing.default-model。
    private final String defaultModel;

    public ChatModelRouter(ChatClientRegistry chatClientRegistry,
                           @Value("${chat.routing.default-model:glm-5.2}") String defaultModel) {
        this.chatClientRegistry = chatClientRegistry;
        this.defaultModel = defaultModel;
    }

    public ChatClient route(String requestedModel) {
        // 调用方没传模型名时，统一回退到默认模型。
        if (!StringUtils.hasText(requestedModel)) {
            log.info("Chat model fallback applied: traceId={}, defaultModel={}",
                    TraceContext.getTraceId(), defaultModel);
            return chatClientRegistry.getRequired(defaultModel);
        }

        // Validate the requested model before lookup so callers get a clearer
        // error containing the list of supported models.
        // 先校验支持性，错误信息里能带出当前所有可用模型 key。
        if (!chatClientRegistry.supports(requestedModel)) {
            throw new IllegalStateException(
                    "Unsupported chat model: " + requestedModel + ", available: " + chatClientRegistry.availableModels()
            );
        }
        log.debug("Chat model routed: traceId={}, requestedModel={}", TraceContext.getTraceId(), requestedModel);
        return chatClientRegistry.getRequired(requestedModel);
    }
}
