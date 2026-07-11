package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.contract.ClarificationKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 单个 session 的待澄清状态。
 * <p>
 * 它不是完整会话上下文，只保存继续完成一次 clarification 所需的最小信息：
 * <ul>
 *     <li>{@code candidateNodeIds}：上轮展示给用户选的候选节点；</li>
 *     <li>{@code originalQuery}：真正需要被继续路由的原始问题；</li>
 *     <li>{@code parentPath}：给用户看的“当前范围”；</li>
 *     <li>{@code expiresAt}：这个澄清状态的过期时间。</li>
 * </ul>
 * 这也是为什么它可以安全地放在 Redis 里做短期缓存，而不需要进入长期会话记忆。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingIntentResolution {
    private String sessionId;
    private List<String> candidateNodeIds;
    private String originalQuery;
    private String parentPath;
    private Instant expiresAt;
    private ClarificationKind clarificationKind;
    private Integer attemptCount;
    private String policyProfileVersion;
    private String contractVersion;
    private List<PendingCandidate> orderedCandidates;

    public int attemptCountOrZero() {
        return attemptCount == null ? 0 : Math.max(attemptCount, 0);
    }

    public List<String> orderedCandidateNodeIds() {
        if (orderedCandidates != null && !orderedCandidates.isEmpty()) {
            return orderedCandidates.stream().map(PendingCandidate::nodeId).toList();
        }
        return candidateNodeIds == null ? List.of() : List.copyOf(candidateNodeIds);
    }

    public record PendingCandidate(String nodeId, double lexicalScore, int rank) {
    }
}
