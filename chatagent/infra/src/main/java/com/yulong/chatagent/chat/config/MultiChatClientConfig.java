package com.yulong.chatagent.chat.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
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
     * Creates the default DeepSeek chat client (deepseek-chat).
     * Used internally for summarization, intent classification, and other non-routing tasks.
     *
     * @param deepSeekChatModel DeepSeek Spring AI model (auto-configured)
     * @return named chat client bean
     */
    @Bean("deepseek-chat")
    public ChatClient deepSeekChatClient(DeepSeekChatModel deepSeekChatModel) {
        // 直接包装 Spring AI 自动配置出来的默认 DeepSeekChatModel。
        return ChatClient.create(deepSeekChatModel);
    }

    /**
     * Creates the DeepSeek Reasoner chat client (deepseek-reasoner).
     * Extracts the shared {@link DeepSeekApi} from the auto-configured model
     * and creates a new model instance with the "deepseek-reasoner" model override.
     *
     * @param deepSeekChatModel auto-configured DeepSeek model (used as API source)
     * @param observationRegistry Micrometer observation registry
     * @return named chat client bean
     */
    @Bean("deepseek-reasoner")
    public ChatClient deepSeekReasonerChatClient(DeepSeekChatModel deepSeekChatModel,
                                                  ObservationRegistry observationRegistry) {
        // 复用自动配置模型里的 DeepSeekApi，避免重新读取 api-key/base-url/webClient 等底层配置。
        DeepSeekApi deepSeekApi = extractField(deepSeekChatModel, "deepSeekApi", DeepSeekApi.class);
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model("deepseek-reasoner")
                .maxTokens(8192)
                .build();
        // 新建一个默认 model=deepseek-reasoner 的 DeepSeekChatModel，再包装成独立 ChatClient Bean。
        DeepSeekChatModel reasonerModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(options)
                .observationRegistry(observationRegistry)
                .build();
        return ChatClient.create(reasonerModel);
    }

    /**
     * Creates the ZhipuAI chat client (glm-4.6).
     *
     * @param zhiPuAiChatModel ZhipuAI Spring AI model (auto-configured)
     * @return named chat client bean
     */
    @Bean("glm-4.6")
    public ChatClient zhiPuAiChatClient(ZhiPuAiChatModel zhiPuAiChatModel) {
        // 直接包装 Spring AI 自动配置出来的默认智谱模型。
        return ChatClient.create(zhiPuAiChatModel);
    }

    /**
     * Creates the ZhipuAI GLM-5.1 chat client.
     * Extracts the shared {@link ZhiPuAiApi} from the auto-configured model
     * and creates a new model instance with the "glm-5.1" model override.
     *
     * @param zhiPuAiChatModel auto-configured ZhipuAI model (used as API source)
     * @return named chat client bean
     */
    @Bean("glm-5.1")
    public ChatClient glm51ChatClient(ZhiPuAiChatModel zhiPuAiChatModel) {
        // 复用自动配置模型里的 ZhiPuAiApi，避免重复配置鉴权和 base-url。
        ZhiPuAiApi zhiPuAiApi = extractField(zhiPuAiChatModel, "zhiPuAiApi", ZhiPuAiApi.class);
        ZhiPuAiChatOptions options = ZhiPuAiChatOptions.builder()
                .model("glm-5.1")
                .temperature(0.35)
                .topP(0.85)
                .maxTokens(4096)
                .build();
        // 基于同一个 ZhiPuAiApi 新建 glm-5.1 模型实例，暴露为独立 Bean。
        ZhiPuAiChatModel glm51Model = new ZhiPuAiChatModel(zhiPuAiApi, options);
        return ChatClient.create(glm51Model);
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
