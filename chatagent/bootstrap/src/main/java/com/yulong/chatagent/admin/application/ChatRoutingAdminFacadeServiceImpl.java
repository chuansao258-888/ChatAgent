package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.UpdateChatRoutingCandidateOverrideRequest;
import com.yulong.chatagent.admin.model.response.GetChatRoutingStateResponse;
import com.yulong.chatagent.admin.model.vo.ChatRoutingCandidateVO;
import com.yulong.chatagent.chat.ChatClientRegistry;
import com.yulong.chatagent.chat.routing.ChatRoutingProperties;
import com.yulong.chatagent.chat.routing.ModelCapabilityResolver;
import com.yulong.chatagent.chat.routing.ModelHealthStore;
import com.yulong.chatagent.chat.routing.RoutingRuntimeOverridesStore;
import com.yulong.chatagent.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the admin routing-state snapshot (configured vs. effective candidate settings, client
 * registration, and circuit-breaker health) and validates then applies runtime candidate overrides.
 */
@Service
@RequiredArgsConstructor
public class ChatRoutingAdminFacadeServiceImpl implements ChatRoutingAdminFacadeService {

    private static final Set<String> SUPPORTED_THINKING_STRATEGIES =
            Set.of("NONE", "ANTHROPIC_THINKING", "ZHIPU_THINKING_FLAG", "MODEL_OVERRIDE");

    private final ChatRoutingProperties properties;
    private final RoutingRuntimeOverridesStore runtimeOverridesStore;
    private final ModelHealthStore modelHealthStore;
    private final ModelCapabilityResolver modelCapabilityResolver;
    private final ChatClientRegistry chatClientRegistry;

    @Override
    public GetChatRoutingStateResponse getRoutingState() {
        Map<String, RoutingRuntimeOverridesStore.CandidateOverride> overrides = runtimeOverridesStore.snapshot();
        Map<String, ModelHealthStore.HealthSnapshot> healthSnapshots = modelHealthStore.snapshot();

        List<ChatRoutingCandidateVO> candidates = safeCandidates().stream()
                .filter(Objects::nonNull)
                .map(candidate -> toCandidateVO(candidate, overrides.get(candidate.getId()), healthSnapshots.get(candidate.getId())))
                .sorted(Comparator
                        .comparing(ChatRoutingCandidateVO::getEffectivePriority, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(ChatRoutingCandidateVO::getId, Comparator.nullsLast(String::compareTo)))
                .toList();

        Set<String> configuredIds = safeCandidates().stream()
                .map(ChatRoutingProperties.CandidateConfig::getId)
                .filter(StringUtils::hasText)
                .collect(TreeSet::new, TreeSet::add, TreeSet::addAll);
        String[] orphanOverrideIds = overrides.keySet().stream()
                .filter(id -> !configuredIds.contains(id))
                .sorted()
                .toArray(String[]::new);

        return GetChatRoutingStateResponse.builder()
                .defaultModel(properties.getDefaultModel())
                .deepThinkingModel(properties.getDeepThinkingModel())
                .firstPacketTimeoutSeconds(properties.getFirstPacketTimeoutSeconds())
                .streamTotalTimeoutSeconds(properties.getStreamTotalTimeoutSeconds())
                .httpConnectTimeoutSeconds(properties.getHttpConnectTimeoutSeconds())
                .httpReadTimeoutSeconds(properties.getHttpReadTimeoutSeconds())
                .registeredModels(chatClientRegistry.availableModels().stream().sorted().toArray(String[]::new))
                .orphanOverrideCandidateIds(orphanOverrideIds)
                .candidates(candidates.toArray(new ChatRoutingCandidateVO[0]))
                .build();
    }

    @Override
    public void updateCandidateOverride(UpdateChatRoutingCandidateOverrideRequest request) {
        if (request == null || !StringUtils.hasText(request.getCandidateId())) {
            throw new BizException("candidateId is required");
        }

        ChatRoutingProperties.CandidateConfig configured = requireConfiguredCandidate(request.getCandidateId());
        if (request.getPriority() != null && request.getPriority() < 0) {
            throw new BizException("priority must be greater than or equal to 0");
        }

        String normalizedStrategy = normalizeThinkingStrategy(request.getThinkingStrategy());
        String normalizedThinkingModel = trimToNull(request.getThinkingModel());
        Boolean supportsThinking = request.getSupportsThinking();

        if (supportsThinking == null
                && normalizedStrategy != null
                && !"NONE".equals(normalizedStrategy)) {
            supportsThinking = Boolean.TRUE;
        }

        if (request.getEnabled() == null
                && request.getPriority() == null
                && supportsThinking == null
                && normalizedStrategy == null
                && normalizedThinkingModel == null) {
            throw new BizException("At least one override field must be provided");
        }

        String effectiveStrategy = normalizedStrategy != null
                ? normalizedStrategy
                : defaultIfBlank(configured.getThinkingStrategy(), "NONE");
        String effectiveThinkingModel = normalizedThinkingModel != null
                ? normalizedThinkingModel
                : trimToNull(configured.getThinkingModel());

        if ("MODEL_OVERRIDE".equals(effectiveStrategy) && !StringUtils.hasText(effectiveThinkingModel)) {
            throw new BizException("thinkingModel is required when thinkingStrategy=MODEL_OVERRIDE");
        }

        runtimeOverridesStore.upsert(new RoutingRuntimeOverridesStore.CandidateOverride(
                configured.getId(),
                request.getEnabled(),
                request.getPriority(),
                supportsThinking,
                normalizedStrategy,
                normalizedThinkingModel
        ));
    }

    @Override
    public void clearCandidateOverride(String candidateId) {
        ChatRoutingProperties.CandidateConfig configured = requireConfiguredCandidate(candidateId);
        runtimeOverridesStore.clear(configured.getId());
    }

    private ChatRoutingCandidateVO toCandidateVO(ChatRoutingProperties.CandidateConfig configured,
                                                 RoutingRuntimeOverridesStore.CandidateOverride override,
                                                 ModelHealthStore.HealthSnapshot healthSnapshot) {
        ChatRoutingProperties.CandidateConfig effective = runtimeOverridesStore.apply(configured);
        return ChatRoutingCandidateVO.builder()
                .id(configured.getId())
                .springClientKey(configured.getSpringClientKey())
                .runtimeOverrideActive(override != null)
                .configuredEnabled(configured.getEnabled())
                .effectiveEnabled(effective.getEnabled())
                .configuredPriority(configured.getPriority())
                .effectivePriority(effective.getPriority())
                .configuredSupportsThinking(configured.getSupportsThinking())
                .effectiveSupportsThinking(modelCapabilityResolver.supportsThinking(effective))
                .configuredThinkingStrategy(defaultIfBlank(configured.getThinkingStrategy(), "NONE"))
                .effectiveThinkingStrategy(defaultIfBlank(effective.getThinkingStrategy(), "NONE"))
                .configuredThinkingModel(trimToNull(configured.getThinkingModel()))
                .effectiveThinkingModel(trimToNull(effective.getThinkingModel()))
                .registered(chatClientRegistry.supports(configured.getSpringClientKey()))
                .circuitState(healthSnapshot == null ? "CLOSED" : healthSnapshot.state())
                .consecutiveFailures(healthSnapshot == null ? 0 : healthSnapshot.consecutiveFailures())
                .reopenInMs(healthSnapshot == null ? 0L : healthSnapshot.reopenInMs())
                .halfOpenStartMs(healthSnapshot == null ? 0L : healthSnapshot.halfOpenStartMs())
                .probeGeneration(healthSnapshot == null ? 0L : healthSnapshot.probeGeneration())
                .build();
    }

    private ChatRoutingProperties.CandidateConfig requireConfiguredCandidate(String candidateId) {
        String normalizedId = trimToNull(candidateId);
        if (!StringUtils.hasText(normalizedId)) {
            throw new BizException("candidateId is required");
        }
        return safeCandidates().stream()
                .filter(Objects::nonNull)
                .filter(candidate -> normalizedId.equals(candidate.getId()))
                .findFirst()
                .orElseThrow(() -> new BizException("Unknown routing candidate: " + candidateId));
    }

    private List<ChatRoutingProperties.CandidateConfig> safeCandidates() {
        if (properties.getCandidates() == null) {
            return List.of();
        }
        return properties.getCandidates();
    }

    private String normalizeThinkingStrategy(String strategy) {
        String normalized = trimToNull(strategy);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!SUPPORTED_THINKING_STRATEGIES.contains(normalized)) {
            throw new BizException("Unsupported thinkingStrategy: " + strategy);
        }
        return normalized;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        String normalized = trimToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
