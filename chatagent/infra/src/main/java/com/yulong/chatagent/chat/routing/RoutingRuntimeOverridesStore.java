package com.yulong.chatagent.chat.routing;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RoutingRuntimeOverridesStore {

    // 运行时覆盖表：key 是候选模型 id，value 是管理端设置的临时覆盖。
    // 这里不直接修改 ChatRoutingProperties，避免污染 YAML 绑定出来的基准配置。
    private final Map<String, CandidateOverride> overridesById = new ConcurrentHashMap<>();

    public ChatRoutingProperties.CandidateConfig apply(ChatRoutingProperties.CandidateConfig source) {
        if (source == null) {
            return null;
        }
        // 每次都复制一份再应用 override，防止运行时选择候选时改到原始配置对象。
        ChatRoutingProperties.CandidateConfig copy = copyOf(source);
        CandidateOverride override = overridesById.get(source.getId());
        if (override == null) {
            return copy;
        }
        // CandidateOverride 的字段为 null 表示“不覆盖该项”，保留 YAML 原值。
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
        // 返回不可变快照，避免调用方绕过 store 直接修改内部 map。
        return Map.copyOf(overridesById);
    }

    public void upsert(CandidateOverride override) {
        // override 必须绑定到一个候选 id，否则无法决定覆盖哪一项模型配置。
        if (override == null || !StringUtils.hasText(override.id())) {
            throw new IllegalArgumentException("Candidate override id cannot be blank");
        }
        overridesById.put(override.id(), override);
    }

    public void clear(String id) {
        // 空 id 不做任何事，便于管理端按需清理时容错。
        if (!StringUtils.hasText(id)) {
            return;
        }
        overridesById.remove(id);
    }

    private static ChatRoutingProperties.CandidateConfig copyOf(ChatRoutingProperties.CandidateConfig source) {
        // 手动复制 CandidateConfig，确保 override 应用只影响本次候选选择结果。
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

    /** 管理端可临时覆盖的候选配置字段；null 表示该字段沿用 YAML。 */
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
