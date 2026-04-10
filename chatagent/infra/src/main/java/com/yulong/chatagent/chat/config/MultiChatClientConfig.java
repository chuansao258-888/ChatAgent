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
        DeepSeekApi deepSeekApi = extractField(deepSeekChatModel, "deepSeekApi", DeepSeekApi.class);
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model("deepseek-reasoner")
                .build();
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
        ZhiPuAiApi zhiPuAiApi = extractField(zhiPuAiChatModel, "zhiPuAiApi", ZhiPuAiApi.class);
        ZhiPuAiChatOptions options = ZhiPuAiChatOptions.builder()
                .model("glm-5.1")
                .build();
        ZhiPuAiChatModel glm51Model = new ZhiPuAiChatModel(zhiPuAiApi, options);
        return ChatClient.create(glm51Model);
    }

    @SuppressWarnings("SameParameterValue")
    private static <T> T extractField(Object target, String fieldName, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(
                    "Failed to extract " + fieldName + " from " + target.getClass().getSimpleName(), e);
        }
    }
}
