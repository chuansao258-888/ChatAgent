package com.yulong.chatagent.chat.routing;

import com.yulong.chatagent.chat.ChatClientRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 YAML 候选配置中选出当前可调用的 {@link ModelTarget} 列表。
 *
 * <p>流程：应用运行时 override → 按 Agent 主/备模型 id 取候选 → 过滤 disabled / 能力不匹配 /
 * 未注册客户端的候选。断路器许可获取延迟到 RoutingLLMService 真正调用前执行，
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
        // Agent 路由只承认 application.yaml 中显式配置的主/备模型顺序。
        // Runtime override 可以禁用或调整能力元数据，但 priority 不再能把第三模型插到前面。
        List<ChatRoutingProperties.CandidateConfig> ordered = configuredAgentCandidates().stream()
                .filter(c -> isUsableCandidate(c, deepThinking))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

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
                .toList();
        log.info("Model candidates selected: deepThinking={}, agentPrimary={}, agentFallback={}, models={}",
                deepThinking,
                properties.getAgentPrimaryModel(),
                properties.getAgentFallbackModel(),
                targets.stream().map(ModelTarget::id).toList());
        return targets;
    }

    private List<ChatRoutingProperties.CandidateConfig> configuredAgentCandidates() {
        Map<String, ChatRoutingProperties.CandidateConfig> candidatesById = new LinkedHashMap<>();
        for (ChatRoutingProperties.CandidateConfig candidate : configuredCandidates()) {
            if (candidate != null && StringUtils.hasText(candidate.getId())) {
                candidatesById.putIfAbsent(candidate.getId(), candidate);
            }
        }
        List<ChatRoutingProperties.CandidateConfig> ordered = new ArrayList<>(2);
        addConfiguredAgentCandidate(ordered, candidatesById, properties.getAgentPrimaryModel());
        addConfiguredAgentCandidate(ordered, candidatesById, properties.getAgentFallbackModel());
        return ordered;
    }

    private void addConfiguredAgentCandidate(List<ChatRoutingProperties.CandidateConfig> ordered,
                                             Map<String, ChatRoutingProperties.CandidateConfig> candidatesById,
                                             String modelId) {
        if (!StringUtils.hasText(modelId)
                || ordered.stream().anyMatch(candidate -> modelId.equals(candidate.getId()))) {
            return;
        }
        ChatRoutingProperties.CandidateConfig candidate = candidatesById.get(modelId);
        if (candidate == null) {
            log.warn("Configured Agent model [{}] is not present in chat.routing.candidates", modelId);
            return;
        }
        ordered.add(candidate);
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

}
