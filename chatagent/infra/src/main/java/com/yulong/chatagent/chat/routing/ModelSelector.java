package com.yulong.chatagent.chat.routing;

import com.yulong.chatagent.chat.ChatClientRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModelSelector {

    private final ChatRoutingProperties properties;
    private final ChatClientRegistry chatClientRegistry;
    private final ModelCapabilityResolver capabilityResolver;
    private final RoutingRuntimeOverridesStore runtimeOverridesStore;

    public List<ModelTarget> selectChatCandidates(boolean deepThinking) {
        String firstChoiceId = resolveFirstChoice(deepThinking);

        List<ChatRoutingProperties.CandidateConfig> ordered = configuredCandidates().stream()
                .filter(c -> isUsableCandidate(c, deepThinking))
                .sorted(Comparator.comparing(
                        ChatRoutingProperties.CandidateConfig::getPriority,
                        Comparator.nullsLast(Integer::compareTo)))
                .collect(Collectors.toCollection(ArrayList::new));

        promoteFirstChoice(ordered, firstChoiceId);

        // 只做无副作用的过滤（enabled / thinking / registry）。
        // 断路器 tryAcquire 由 RoutingLLMService 在真正调用模型前延迟执行，避免批量 HALF_OPEN 泄露。
        List<ModelTarget> targets = ordered.stream()
                .filter(c -> {
                    if (!chatClientRegistry.supports(c.getSpringClientKey())) {
                        log.warn("Model [{}] spring-client-key [{}] not found in registry",
                                c.getId(), c.getSpringClientKey());
                        return false;
                    }
                    return true;
                })
                .map(c -> new ModelTarget(c.getId(), c, chatClientRegistry.getRequired(c.getSpringClientKey())))
                .collect(Collectors.toList());
        log.info("Model candidates selected: deepThinking={}, firstChoice={}, models={}",
                deepThinking,
                firstChoiceId,
                targets.stream().map(ModelTarget::id).toList());
        return targets;
    }

    private String resolveFirstChoice(boolean deepThinking) {
        if (deepThinking && properties.getDeepThinkingModel() != null
                && !properties.getDeepThinkingModel().isBlank()) {
            return properties.getDeepThinkingModel();
        }
        return properties.getDefaultModel();
    }

    private List<ChatRoutingProperties.CandidateConfig> configuredCandidates() {
        if (properties.getCandidates() == null) {
            return Collections.emptyList();
        }
        return properties.getCandidates().stream()
                .map(runtimeOverridesStore::apply)
                .toList();
    }

    private boolean isUsableCandidate(ChatRoutingProperties.CandidateConfig candidate, boolean deepThinking) {
        if (candidate == null) {
            return false;
        }
        if (!Boolean.TRUE.equals(candidate.getEnabled())) {
            log.debug("Model [{}] skipped because it is disabled", candidate.getId());
            return false;
        }
        if (!StringUtils.hasText(candidate.getId()) || !StringUtils.hasText(candidate.getSpringClientKey())) {
            log.warn("Model candidate skipped because id or spring-client-key is blank: id={}, springClientKey={}",
                    candidate.getId(), candidate.getSpringClientKey());
            return false;
        }
        if (deepThinking && !capabilityResolver.supportsThinking(candidate)) {
            log.debug("Model [{}] skipped because supportsThinking resolved to false", candidate.getId());
            return false;
        }
        return true;
    }

    private void promoteFirstChoice(List<ChatRoutingProperties.CandidateConfig> ordered, String firstChoiceId) {
        if (!StringUtils.hasText(firstChoiceId)) {
            return;
        }
        ordered.stream()
                .filter(c -> firstChoiceId.equals(c.getId()))
                .findFirst()
                .ifPresent(first -> {
                    ordered.remove(first);
                    ordered.add(0, first);
                });
    }
}
