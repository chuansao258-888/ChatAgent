package com.yulong.chatagent.chat.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;

@Configuration
/**
 * Declares named {@link ChatClient} beans for every supported chat model.
 *
 * <p>The bean names are used as routing keys by {@code ChatModelRouter}.</p>
 *
 * <p>中文说明：这里把多个具体模型注册成不同名字的 ChatClient Bean。
 * chat.routing.candidates[].spring-client-key 最终就是拿这些 Bean 名称去 ChatClientRegistry 查。</p>
 */
public class MultiChatClientConfig {
    /**
     * Creates the default DeepSeek chat client (deepseek-v4-flash).
     * Used internally for summarization, intent classification, and other non-routing tasks.
     *
     * @param deepSeekChatModel DeepSeek Spring AI model (auto-configured)
     * @return named chat client bean
     */
    @Bean("deepseek-v4-flash")
    public ChatClient deepSeekV4FlashChatClient(DeepSeekChatModel deepSeekChatModel) {
        // 直接包装 Spring AI 自动配置出来的默认 DeepSeekChatModel。
        return ChatClient.create(deepSeekChatModel);
    }

    /**
     * Creates the DeepSeek v4 pro chat client (deepseek-v4-pro).
     * Extracts the shared {@link DeepSeekApi} from the auto-configured model
     * and creates a new model instance with the "deepseek-v4-pro" model override.
     *
     * @param deepSeekChatModel auto-configured DeepSeek model (used as API source)
     * @param observationRegistry Micrometer observation registry
     * @return named chat client bean
     */
    @Bean("deepseek-v4-pro")
    public ChatClient deepSeekV4ProChatClient(DeepSeekChatModel deepSeekChatModel,
                                               ObservationRegistry observationRegistry) {
        // 复用自动配置模型里的 DeepSeekApi，避免重新读取 api-key/base-url/webClient 等底层配置。
        DeepSeekApi deepSeekApi = extractField(deepSeekChatModel, "deepSeekApi", DeepSeekApi.class);
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model("deepseek-v4-pro")
                .maxTokens(8192)
                .build();
        // 新建一个默认 model=deepseek-v4-pro 的 DeepSeekChatModel，再包装成独立 ChatClient Bean。
        DeepSeekChatModel proModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(options)
                .observationRegistry(observationRegistry)
                .build();
        return ChatClient.create(proModel);
    }

    /**
     * Creates the Z.AI multimodal chat client (glm-4.6v-flash).
     *
     * @param zhiPuAiChatModel ZhipuAI Spring AI model (auto-configured)
     * @return named chat client bean
     */
    @Bean("glm-4.6v-flash")
    public ChatClient zaiGlm46vFlashChatClient(ZhiPuAiChatModel zhiPuAiChatModel) {
        // 复用 ZhiPuAi 协议客户端调用 Z.AI Coding 的 OpenAI-compatible VLM endpoint。
        return ChatClient.create(zhiPuAiChatModel);
    }

    /**
     * Creates the Z.AI coding-plan GLM-5.2 chat client through the Anthropic-compatible API.
     *
     * @param anthropicChatModel Anthropic-compatible Spring AI model (auto-configured for Z.AI)
     * @return named chat client bean
     */
    @Bean("glm-5.2")
    public ChatClient zaiCodingGlm52ChatClient(AnthropicChatModel anthropicChatModel) {
        return ChatClient.create(anthropicChatModel);
    }

    @SuppressWarnings("SameParameterValue")
    private static <T> T extractField(Object target, String fieldName, Class<T> type) {
        try {
            // 反射读取 Spring AI 模型里的私有 API 字段，例如 deepSeekApi / zhiPuAiApi。
            // 这样新建派生模型时能复用自动配置好的底层客户端。
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(
                    "Failed to extract " + fieldName + " from " + target.getClass().getSimpleName(), e);
        }
    }
}
