package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.support.dto.IntentNodeDTO;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 一轮对话准备阶段产出的「最终意图解析结果」。
 *
 * 它是 ConversationTurnPreparationService 和 AgentRuntime 之间的边界对象：
 * 1. kind 决定这轮是 KB / TOOL / SYSTEM 等哪种处理方式；
 * 2. path 保存从根节点到叶子节点的完整路径；
 * 3. scopedKbIds / allowedTools 是叶子意图收敛出的运行时资源范围；
 * 4. systemPromptOverride 只给 SYSTEM 直答使用。
 */
public record IntentResolution(
        IntentKind kind,
        // 完整路径很重要：后续 rewrite、日志、澄清恢复都会用 pathLabel 表达“命中了哪条路”。
        List<IntentNodeDTO> path,
        List<String> scopedKbIds,
        ScopePolicy scopePolicy,
        List<String> allowedTools,
        String systemPromptOverride
) {

    public IntentResolution {
        // record 构造时做不可变拷贝，避免后续调用方误改 path/kb/tool 集合。
        path = path == null ? List.of() : List.copyOf(path);
        scopedKbIds = scopedKbIds == null ? List.of() : List.copyOf(scopedKbIds);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }

    /**
     * 把路径转成人类可读的标签。
     *
     * 例：课程 > 作业 > 延期申请。
     * 这个字符串会进入 query rewrite prompt，也会用于日志和后台排查。
     */
    public String pathLabel() {
        return path.stream()
                .map(IntentNodeDTO::getName)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining(" > "));
    }
}
