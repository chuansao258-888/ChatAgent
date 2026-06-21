package com.yulong.chatagent.chat.routing;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 原始厂商 SSE 通道需要的 provider 绑定表。
 *
 * <p>按 spring-client-key 暴露 {@link ProviderBinding}，供 ProviderDirectStreamSupport 判断某个候选能否
 * 走原始 SSE 通道。绑定复用自动配置出来的 ChatModel 与底层 Api，避免重复配置鉴权和连接。</p>
 */
@Component
public class ChatModelProviderRegistry {

    // spring-client-key -> 原始厂商绑定。
    // 这个表服务于 ProviderDirectStreamSupport，用来判断某个候选能否走原始 SSE 通道。
    private final Map<String, ProviderBinding> bindingsByKey;

    public ChatModelProviderRegistry(ObjectProvider<DeepSeekChatModel> deepSeekChatModelProvider,
                                     ObjectProvider<DeepSeekApi> deepSeekApiProvider,
                                     ObjectProvider<ZhiPuAiChatModel> zhiPuAiChatModelProvider,
                                     ObjectProvider<ZhiPuAiApi> zhiPuAiApiProvider,
                                     ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        // 使用 LinkedHashMap 构造，便于调试时保持注册顺序；最终用 Map.copyOf 固化为不可变表。
        Map<String, ProviderBinding> bindings = new LinkedHashMap<>();

        // ObjectProvider.getIfAvailable() 允许某个 provider 缺失时不让整个应用启动失败。
        DeepSeekChatModel deepSeekChatModel = deepSeekChatModelProvider.getIfAvailable();
        DeepSeekApi deepSeekApi = deepSeekApiProvider.getIfAvailable();
        if (deepSeekApi == null && deepSeekChatModel != null) {
            deepSeekApi = extractField(deepSeekChatModel, "deepSeekApi", DeepSeekApi.class);
        }
        if (deepSeekChatModel != null && deepSeekApi != null) {
            // 自动配置创建的默认 DeepSeekChatModel，对应 deepseek-v4-flash。
            bindings.put("deepseek-v4-flash", new DeepSeekBinding("deepseek-v4-flash", deepSeekChatModel, deepSeekApi));

            // 复用同一个 DeepSeekApi，再创建一个默认 model=deepseek-v4-pro 的 ChatModel。
            // 这样路由候选可以直接使用 spring-client-key=deepseek-v4-pro。
            DeepSeekChatOptions proOptions = DeepSeekChatOptions.builder()
                    .model("deepseek-v4-pro").maxTokens(8192).build();
            ObservationRegistry obsRegistry = observationRegistryProvider.getIfAvailable(
                    () -> ObservationRegistry.NOOP);
            DeepSeekChatModel proModel = DeepSeekChatModel.builder()
                    .deepSeekApi(deepSeekApi)
                    .defaultOptions(proOptions)
                    .observationRegistry(obsRegistry)
                    .build();
            bindings.put("deepseek-v4-pro",
                    new DeepSeekBinding("deepseek-v4-pro", proModel, deepSeekApi));
        }

        ZhiPuAiChatModel zhiPuAiChatModel = zhiPuAiChatModelProvider.getIfAvailable();
        ZhiPuAiApi zhiPuAiApi = zhiPuAiApiProvider.getIfAvailable();
        if (zhiPuAiChatModel != null && zhiPuAiApi != null) {
            // 自动配置创建的 Z.AI Coding VLM 模型，对应 glm-4.6v-flash。
            bindings.put("glm-4.6v-flash",
                    new ZhiPuAiBinding("glm-4.6v-flash", zhiPuAiChatModel, zhiPuAiApi));
        }

        this.bindingsByKey = Map.copyOf(bindings);
    }

    public Optional<ProviderBinding> find(String springClientKey) {
        // 找不到 binding 时返回 Optional.empty，外层会回退到 ChatClient.stream()。
        if (!StringUtils.hasText(springClientKey)) {
            return Optional.empty();
        }
        return Optional.ofNullable(bindingsByKey.get(springClientKey));
    }

    private static <T> T extractField(Object target, String fieldName, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (NoSuchFieldException | IllegalAccessException | ClassCastException e) {
            return null;
        }
    }

    // 旧调试入口已停用：当前生产路由只通过 find(springClientKey) 查询具体 binding。
    // public Set<String> registeredKeys() {
    //     return bindingsByKey.keySet();
    // }

    public enum ProviderType {
        DEEPSEEK,
        ZHIPU_AI
    }

    /**
     * 原始 SSE 通道需要的最小厂商绑定信息。
     *
     * <p>它既提供 spring-client-key，也暴露 provider 类型和 thinking 能力；
     * 具体厂商 record 还会携带 ChatModel 与底层 Api，供 ProviderDirectStreamSupport 复用。</p>
     */
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
            // DeepSeek 的 thinking 通常通过选择 v4-pro/reasoning-capable 模型实现，
            // 不是统一的 provider thinking flag；具体候选可用 YAML supports-thinking 覆盖。
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
            // 智谱有明确 thinking flag，因此 provider 层可以默认声明支持。
            return true;
        }
    }
}
