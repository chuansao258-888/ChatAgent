package com.yulong.chatagent.chat.routing;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

@Component
public class ModelCapabilityResolver {

    // providerRegistry 能从原始厂商绑定里提供一部分能力信息；
    // YAML 显式配置仍然优先，因为它最贴近当前业务希望如何路由。
    private final ChatModelProviderRegistry providerRegistry;

    public ModelCapabilityResolver(ChatModelProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public boolean supportsThinking(ChatRoutingProperties.CandidateConfig candidate) {
        // 没有候选配置时，不能推断能力，按不支持处理。
        if (candidate == null) {
            return false;
        }
        // YAML / 运行时 override 显式声明优先级最高。
        // Boolean 允许 null，null 表示“没有明确配置，继续自动推断”。
        if (candidate.getSupportsThinking() != null) {
            return candidate.getSupportsThinking();
        }
        // 如果配置了非 NONE 的 thinking-strategy，说明调用方已经指定了开启 thinking 的方式。
        if (StringUtils.hasText(candidate.getThinkingStrategy())
                && !"NONE".equalsIgnoreCase(candidate.getThinkingStrategy())) {
            return true;
        }
        String key = candidate.getSpringClientKey();
        if (!StringUtils.hasText(key)) {
            return false;
        }
        // 再尝试从原始 provider binding 中读取能力。
        // 注意：如果 registry 找到了 binding，即使返回 false，也不会继续走名字兜底。
        Optional<Boolean> providerCapability = providerRegistry.find(key)
                .map(ChatModelProviderRegistry.ProviderBinding::supportsThinking);
        if (providerCapability.isPresent()) {
            return providerCapability.get();
        }
        // 最后才按 key 名称兜底推断，适配未来未接入 providerRegistry 的模型 key。
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("glm")
                || normalized.contains("zhipu")
                || normalized.contains("reasoner");
    }
}
