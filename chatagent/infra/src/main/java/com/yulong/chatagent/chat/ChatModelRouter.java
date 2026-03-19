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
 */
public class ChatModelRouter {

    private final ChatClientRegistry chatClientRegistry;
    private final String defaultModel;

    public ChatModelRouter(ChatClientRegistry chatClientRegistry,
                           @Value("${chat.routing.default-model:deepseek-chat}") String defaultModel) {
        this.chatClientRegistry = chatClientRegistry;
        this.defaultModel = defaultModel;
    }

    public ChatClient route(String requestedModel) {
        if (!StringUtils.hasText(requestedModel)) {
            log.info("Chat model fallback applied: traceId={}, defaultModel={}",
                    TraceContext.getTraceId(), defaultModel);
            return chatClientRegistry.getRequired(defaultModel);
        }

        // Validate the requested model before lookup so callers get a clearer
        // error containing the list of supported models.
        if (!chatClientRegistry.supports(requestedModel)) {
            throw new IllegalStateException(
                    "Unsupported chat model: " + requestedModel + ", available: " + chatClientRegistry.availableModels()
            );
        }
        log.debug("Chat model routed: traceId={}, requestedModel={}", TraceContext.getTraceId(), requestedModel);
        return chatClientRegistry.getRequired(requestedModel);
    }
}
