package com.yulong.chatagent.chat.routing;

import com.yulong.chatagent.chat.ChatClientRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fails fast when the restart-required Agent primary/fallback routing contract is misconfigured.
 */
@Component
@RequiredArgsConstructor
public class AgentModelRoutingConfigurationValidator {

    private final ChatRoutingProperties properties;
    private final ChatClientRegistry chatClientRegistry;
    private final ModelCapabilityResolver capabilityResolver;

    @PostConstruct
    public void validate() {
        String primary = requireModelId(properties.getAgentPrimaryModel(), "chat.routing.agent-primary-model");
        String fallback = requireModelId(properties.getAgentFallbackModel(), "chat.routing.agent-fallback-model");
        if (primary.equals(fallback)) {
            throw new IllegalStateException(
                    "chat.routing.agent-primary-model and chat.routing.agent-fallback-model must be distinct");
        }

        Map<String, ChatRoutingProperties.CandidateConfig> candidatesById = candidateMap(properties.getCandidates());
        validateCandidate(primary, "chat.routing.agent-primary-model", candidatesById);
        validateCandidate(fallback, "chat.routing.agent-fallback-model", candidatesById);
    }

    private String requireModelId(String modelId, String propertyName) {
        if (!StringUtils.hasText(modelId)) {
            throw new IllegalStateException(propertyName + " must not be blank");
        }
        return modelId.trim();
    }

    private Map<String, ChatRoutingProperties.CandidateConfig> candidateMap(
            List<ChatRoutingProperties.CandidateConfig> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalStateException("chat.routing.candidates must include Agent primary/fallback candidates");
        }
        Map<String, ChatRoutingProperties.CandidateConfig> byId = new LinkedHashMap<>();
        for (ChatRoutingProperties.CandidateConfig candidate : candidates) {
            if (candidate == null || !StringUtils.hasText(candidate.getId())) {
                throw new IllegalStateException("chat.routing.candidates contains a blank candidate id");
            }
            ChatRoutingProperties.CandidateConfig previous = byId.putIfAbsent(candidate.getId(), candidate);
            if (previous != null) {
                throw new IllegalStateException(
                        "chat.routing.candidates contains duplicate model id: " + candidate.getId());
            }
        }
        return byId;
    }

    private void validateCandidate(String modelId,
                                   String propertyName,
                                   Map<String, ChatRoutingProperties.CandidateConfig> candidatesById) {
        ChatRoutingProperties.CandidateConfig candidate = candidatesById.get(modelId);
        if (candidate == null) {
            throw new IllegalStateException(propertyName + " references unknown candidate id: " + modelId);
        }
        if (!Boolean.TRUE.equals(candidate.getEnabled())) {
            throw new IllegalStateException(propertyName + " references disabled candidate id: " + modelId);
        }
        if (!StringUtils.hasText(candidate.getSpringClientKey())) {
            throw new IllegalStateException(
                    propertyName + " candidate has blank spring-client-key: " + modelId);
        }
        if (!chatClientRegistry.supports(candidate.getSpringClientKey())) {
            throw new IllegalStateException(propertyName + " candidate spring-client-key is not registered: "
                    + candidate.getSpringClientKey());
        }
        if (!capabilityResolver.supportsThinking(candidate)) {
            throw new IllegalStateException(
                    propertyName + " candidate must support DeepThink routing: " + modelId);
        }
    }
}
