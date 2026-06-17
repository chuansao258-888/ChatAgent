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

/**
 * 从 YAML 候选配置中选出当前可调用的 {@link ModelTarget} 列表。
 *
 * <p>流程：应用运行时 override → 过滤 disabled / 能力不匹配 / 未注册客户端的候选 → 按 priority
 * 升序排序 → 把首选模型提升到第一位。断路器许可获取延迟到 RoutingLLMService 真正调用前执行，
 * 避免在选择阶段批量占用 HALF_OPEN 探针配额。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelSelector {

    // YAML 绑定后的静态路由配置。
    private final ChatRoutingProperties properties;
    // 普通 ChatClient 注册表；select 最终必须拿到可调用的 ChatClient。
    private final ChatClientRegistry chatClientRegistry;
    // 判断候选模型能否参与 deepThinking 路由。
    private final ModelCapabilityResolver capabilityResolver;
    // 管理运行时临时开关/优先级覆盖，避免每次都改 YAML。
    private final RoutingRuntimeOverridesStore runtimeOverridesStore;

    public List<ModelTarget> selectChatCandidates(boolean deepThinking) {
        // deepThinking 请求优先使用 deepThinkingModel；普通请求使用 defaultModel。
        // 这个 firstChoice 后面会被移动到候选列表第一位。
        String firstChoiceId = resolveFirstChoice(deepThinking);

        // 先应用运行时 override，再过滤可用性，最后按 priority 排序。
        // collect 到 ArrayList 是为了后续 promoteFirstChoice 能 remove/add。
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
                    // 候选配置必须能在 ChatClientRegistry 中找到对应客户端，否则不能实际调用。
                    if (!chatClientRegistry.supports(c.getSpringClientKey())) {
                        log.warn("Model [{}] spring-client-key [{}] not found in registry",
                                c.getId(), c.getSpringClientKey());
                        return false;
                    }
                    return true;
                })
                // CandidateConfig 只是配置；ModelTarget = 配置 + 真正可调用的 ChatClient。
                .map(c -> new ModelTarget(c.getId(), c, chatClientRegistry.getRequired(c.getSpringClientKey())))
                .collect(Collectors.toList());
        log.info("Model candidates selected: deepThinking={}, firstChoice={}, models={}",
                deepThinking,
                firstChoiceId,
                targets.stream().map(ModelTarget::id).toList());
        return targets;
    }

    private String resolveFirstChoice(boolean deepThinking) {
        // deepThinking=true 时，如果显式配置了 deepThinkingModel，就让它成为首选。
        if (deepThinking && properties.getDeepThinkingModel() != null
                && !properties.getDeepThinkingModel().isBlank()) {
            return properties.getDeepThinkingModel();
        }
        return properties.getDefaultModel();
    }

    private List<ChatRoutingProperties.CandidateConfig> configuredCandidates() {
        // candidates 为空时返回空列表，避免上层 NPE。
        if (properties.getCandidates() == null) {
            return Collections.emptyList();
        }
        // 每次选择候选时动态应用 runtime override，保证管理端修改能即时影响路由。
        return properties.getCandidates().stream()
                .map(runtimeOverridesStore::apply)
                .toList();
    }

    private boolean isUsableCandidate(ChatRoutingProperties.CandidateConfig candidate, boolean deepThinking) {
        // 这里只做静态/无副作用判断；健康状态由 RoutingLLMService 真正发起调用前处理。
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
        // deepThinking 请求不能选不支持 thinking 的模型。
        if (deepThinking && !capabilityResolver.supportsThinking(candidate)) {
            log.debug("Model [{}] skipped because supportsThinking resolved to false", candidate.getId());
            return false;
        }
        return true;
    }

    private void promoteFirstChoice(List<ChatRoutingProperties.CandidateConfig> ordered, String firstChoiceId) {
        // 将默认模型/深度思考模型提升到第一位，但不破坏其他候选原有 priority 顺序。
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
