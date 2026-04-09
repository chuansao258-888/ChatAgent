package com.yulong.chatagent.chat.routing;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RoutingRuntimeOverridesStore {

    private final Map<String, CandidateOverride> overridesById = new ConcurrentHashMap<>();

    public ChatRoutingProperties.CandidateConfig apply(ChatRoutingProperties.CandidateConfig source) {
        if (source == null) {
            return null;
        }
        ChatRoutingProperties.CandidateConfig copy = copyOf(source);
        CandidateOverride override = overridesById.get(source.getId());
        if (override == null) {
            return copy;
        }
        if (override.enabled() != null) {
            copy.setEnabled(override.enabled());
        }
        if (override.priority() != null) {
            copy.setPriority(override.priority());
        }
        if (override.supportsThinking() != null) {
            copy.setSupportsThinking(override.supportsThinking());
        }
        if (override.thinkingStrategy() != null) {
            copy.setThinkingStrategy(override.thinkingStrategy());
        }
        if (override.thinkingModel() != null) {
            copy.setThinkingModel(override.thinkingModel());
        }
        return copy;
    }

    public Map<String, CandidateOverride> snapshot() {
        return Map.copyOf(overridesById);
    }

    public void upsert(CandidateOverride override) {
        if (override == null || !StringUtils.hasText(override.id())) {
            throw new IllegalArgumentException("Candidate override id cannot be blank");
        }
        overridesById.put(override.id(), override);
    }

    public void clear(String id) {
        if (!StringUtils.hasText(id)) {
            return;
        }
        overridesById.remove(id);
    }

    private static ChatRoutingProperties.CandidateConfig copyOf(ChatRoutingProperties.CandidateConfig source) {
        ChatRoutingProperties.CandidateConfig copy = new ChatRoutingProperties.CandidateConfig();
        copy.setId(source.getId());
        copy.setSpringClientKey(source.getSpringClientKey());
        copy.setPriority(source.getPriority());
        copy.setEnabled(source.getEnabled());
        copy.setSupportsThinking(source.getSupportsThinking());
        copy.setThinkingStrategy(source.getThinkingStrategy());
        copy.setThinkingModel(source.getThinkingModel());
        return copy;
    }

    public record CandidateOverride(
            String id,
            Boolean enabled,
            Integer priority,
            Boolean supportsThinking,
            String thinkingStrategy,
            String thinkingModel
    ) {
    }
}
