package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.support.dto.IntentNodeDTO;

import java.util.List;

/**
 * 意图路由结果。
 * <p>
 * 这个对象是 {@link IntentRouter} 返回给上层 prepare 阶段的统一输出，
 * 用来表达“当前这轮路由到了哪一种状态”。
 * 它只有三种业务语义：
 * <ul>
 *     <li>{@code resolved}：已经拿到了明确的 {@link IntentResolution}；</li>
 *     <li>{@code clarification}：当前还不能确定，需要用户补充选择；</li>
 *     <li>{@code none}：没有命中任何稳定意图，交给上层决定是否透传给 Agent。</li>
 * </ul>
 * 因此它不是最终执行结果，而是 prepare 阶段的分流中间态。
 *
 * @param resolution 已经解析出的最终意图边界；只有 resolved 场景下才非空
 * @param clarificationCandidates 需要让用户二次确认时展示的候选节点列表
 * @param parentPath 当前候选所属的父级路径标签，用于给用户显示“当前范围”
 */
public record IntentRoutingResult(
        IntentResolution resolution,
        List<IntentNodeDTO> clarificationCandidates,
        String parentPath
) {
    public IntentRoutingResult {
        // clarificationCandidates 一律做不可变拷贝，避免后续上层误修改路由结果。
        clarificationCandidates = clarificationCandidates == null ? List.of() : List.copyOf(clarificationCandidates);
    }

    public static IntentRoutingResult none() {
        // none 表示没有可用 resolution，也没有可供澄清的候选项。
        return new IntentRoutingResult(null, List.of(), null);
    }

    public static IntentRoutingResult resolved(IntentResolution resolution) {
        // resolved 表示这一轮已经得到明确意图，可以继续向下做 rewrite / dispatch。
        return new IntentRoutingResult(resolution, List.of(), null);
    }

    public static IntentRoutingResult clarification(List<IntentNodeDTO> candidates, String parentPath) {
        // clarification 表示“当前候选不够明确”，需要先让用户从多个候选里二选一或多选一。
        // 这里仍然保留 parentPath，是为了让澄清文案能说明“你现在是在什么范围内选”。
        return new IntentRoutingResult(null, candidates, parentPath);
    }

    public boolean requiresClarification() {
        // 只要 clarificationCandidates 非空，上层就应优先走 direct reply 澄清分支。
        return !clarificationCandidates.isEmpty();
    }

    public boolean hasResolution() {
        // resolution 是否可用，是 prepare 阶段后续直答 / dispatch 判断的另一个关键条件。
        return resolution != null;
    }
}
