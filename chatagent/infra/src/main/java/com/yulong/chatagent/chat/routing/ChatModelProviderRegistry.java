package com.yulong.chatagent.chat.routing;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class ChatModelProviderRegistry {

    private final Map<String, ProviderBinding> bindingsByKey;

    public ChatModelProviderRegistry(ObjectProvider<DeepSeekChatModel> deepSeekChatModelProvider,
                                     ObjectProvider<DeepSeekApi> deepSeekApiProvider,
                                     ObjectProvider<ZhiPuAiChatModel> zhiPuAiChatModelProvider,
                                     ObjectProvider<ZhiPuAiApi> zhiPuAiApiProvider,
                                     ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        Map<String, ProviderBinding> bindings = new LinkedHashMap<>();

        DeepSeekChatModel deepSeekChatModel = deepSeekChatModelProvider.getIfAvailable();
        DeepSeekApi deepSeekApi = deepSeekApiProvider.getIfAvailable();
        if (deepSeekChatModel != null && deepSeekApi != null) {
            bindings.put("deepseek-chat", new DeepSeekBinding("deepseek-chat", deepSeekChatModel, deepSeekApi));

            DeepSeekChatOptions reasonerOptions = DeepSeekChatOptions.builder()
                    .model("deepseek-reasoner").build();
            ObservationRegistry obsRegistry = observationRegistryProvider.getIfAvailable(
                    () -> ObservationRegistry.NOOP);
            DeepSeekChatModel reasonerModel = DeepSeekChatModel.builder()
                    .deepSeekApi(deepSeekApi)
                    .defaultOptions(reasonerOptions)
                    .observationRegistry(obsRegistry)
                    .build();
            bindings.put("deepseek-reasoner",
                    new DeepSeekBinding("deepseek-reasoner", reasonerModel, deepSeekApi));
        }

        ZhiPuAiChatModel zhiPuAiChatModel = zhiPuAiChatModelProvider.getIfAvailable();
        ZhiPuAiApi zhiPuAiApi = zhiPuAiApiProvider.getIfAvailable();
        if (zhiPuAiChatModel != null && zhiPuAiApi != null) {
            bindings.put("glm-4.6", new ZhiPuAiBinding("glm-4.6", zhiPuAiChatModel, zhiPuAiApi));

            ZhiPuAiChatOptions glm51Options = ZhiPuAiChatOptions.builder()
                    .model("glm-5.1").build();
            ZhiPuAiChatModel glm51Model = new ZhiPuAiChatModel(zhiPuAiApi, glm51Options);
            bindings.put("glm-5.1", new ZhiPuAiBinding("glm-5.1", glm51Model, zhiPuAiApi));
        }

        this.bindingsByKey = Map.copyOf(bindings);
    }

    public Optional<ProviderBinding> find(String springClientKey) {
        if (!StringUtils.hasText(springClientKey)) {
            return Optional.empty();
        }
        return Optional.ofNullable(bindingsByKey.get(springClientKey));
    }

    public Set<String> registeredKeys() {
        return bindingsByKey.keySet();
    }

    public enum ProviderType {
        DEEPSEEK,
        ZHIPU_AI
    }

    public sealed interface ProviderBinding permits DeepSeekBinding, ZhiPuAiBinding {
        String springClientKey();

        ProviderType providerType();

        boolean supportsThinking();
    }

    public record DeepSeekBinding(
            String springClientKey,
            DeepSeekChatModel chatModel,
            DeepSeekApi api
    ) implements ProviderBinding {

        @Override
        public ProviderType providerType() {
            return ProviderType.DEEPSEEK;
        }

        @Override
        public boolean supportsThinking() {
            return false;
        }
    }

    public record ZhiPuAiBinding(
            String springClientKey,
            ZhiPuAiChatModel chatModel,
            ZhiPuAiApi api
    ) implements ProviderBinding {

        @Override
        public ProviderType providerType() {
            return ProviderType.ZHIPU_AI;
        }

        @Override
        public boolean supportsThinking() {
            return true;
        }
    }
}
