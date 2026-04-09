package com.yulong.chatagent.chat.routing;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

@Component
public class ModelCapabilityResolver {

    private final ChatModelProviderRegistry providerRegistry;

    public ModelCapabilityResolver(ChatModelProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public boolean supportsThinking(ChatRoutingProperties.CandidateConfig candidate) {
        if (candidate == null) {
            return false;
        }
        if (candidate.getSupportsThinking() != null) {
            return candidate.getSupportsThinking();
        }
        if (StringUtils.hasText(candidate.getThinkingStrategy())
                && !"NONE".equalsIgnoreCase(candidate.getThinkingStrategy())) {
            return true;
        }
        String key = candidate.getSpringClientKey();
        if (!StringUtils.hasText(key)) {
            return false;
        }
        Optional<Boolean> providerCapability = providerRegistry.find(key)
                .map(ChatModelProviderRegistry.ProviderBinding::supportsThinking);
        if (providerCapability.isPresent()) {
            return providerCapability.get();
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("glm")
                || normalized.contains("zhipu")
                || normalized.contains("reasoner");
    }
}
