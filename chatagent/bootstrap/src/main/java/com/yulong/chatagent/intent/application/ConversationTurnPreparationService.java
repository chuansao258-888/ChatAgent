package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 单轮对话准备器。
 * <p>
 * 它负责把一条“刚进入系统的用户输入”整理成 turn 级决策结果：
 * <ul>
 *     <li>是否需要澄清；</li>
 *     <li>是否命中 SYSTEM 直答；</li>
 *     <li>是否需要 query rewrite；</li>
 *     <li>最终是 direct reply 还是 dispatch 给 Agent。</li>
 * </ul>
 * 所以这个类不是单纯的“意图分类器”，而是 conversation 编排链上的分流枢纽。
 */
@Component
public class ConversationTurnPreparationService {

    private final IntentTreeCacheManager intentTreeCacheManager;
    private final PendingIntentResolutionStore pendingIntentResolutionStore;
    private final ClarificationResolver clarificationResolver;
    private final ClarificationResponseBuilder clarificationResponseBuilder;
    private final IntentRouter intentRouter;
    private final QueryRewriter queryRewriter;
    private final SystemIntentResponseRenderer systemIntentResponseRenderer;

    public ConversationTurnPreparationService(IntentTreeCacheManager intentTreeCacheManager,
                                              PendingIntentResolutionStore pendingIntentResolutionStore,
                                              ClarificationResolver clarificationResolver,
                                              ClarificationResponseBuilder clarificationResponseBuilder,
                                              IntentRouter intentRouter,
                                              QueryRewriter queryRewriter,
                                              SystemIntentResponseRenderer systemIntentResponseRenderer) {
        this.intentTreeCacheManager = intentTreeCacheManager;
        this.pendingIntentResolutionStore = pendingIntentResolutionStore;
        this.clarificationResolver = clarificationResolver;
        this.clarificationResponseBuilder = clarificationResponseBuilder;
        this.intentRouter = intentRouter;
        this.queryRewriter = queryRewriter;
        this.systemIntentResponseRenderer = systemIntentResponseRenderer;
    }

    /**
     * 把一条用户输入整理成“本轮该怎么走”的编排结果。
     * <p>
     * 可以把它看成 conversation 主链进入 Agent 之前的最后一道分流关口：
     * <ul>
     *     <li>如果命中澄清，直接回复用户，不进 Agent；</li>
     *     <li>如果命中 SYSTEM，直接在编排层返回答案；</li>
     *     <li>否则带着 resolution / rewrite 后的 query 继续 dispatch。</li>
     * </ul>
     */
    public TurnPreparationResult prepare(String agentId, String sessionId, String userInput) {
        // 基础入参不完整时，直接透传给 Agent。
        // 这里不抛错，是因为某些场景下“没有可用路由结果”本身就是允许的默认路径。
        if (!StringUtils.hasText(agentId) || !StringUtils.hasText(sessionId) || !StringUtils.hasText(userInput)) {
            return TurnPreparationResult.passthrough();
        }

        IntentTreeSnapshot snapshot = intentTreeCacheManager.loadActiveSnapshot(agentId);
        if (snapshot.isEmpty()) {
            // 当前 agent 没有可用 intent tree，说明这一轮不做意图边界控制。
            // 同时清掉历史 pending clarification，避免用户卡在过期澄清状态里。
            pendingIntentResolutionStore.delete(sessionId);
            return TurnPreparationResult.passthrough();
        }

        PendingIntentResolution pending = pendingIntentResolutionStore.get(sessionId);
        IntentRoutingResult routingResult;
        String canonicalQuery = userInput.trim();

        if (pending != null) {
            // clarification continuation 主链：
            // 上一轮已经问过用户“你是 A 还是 B”，这轮就先把这句话当作“澄清回答”来解释，
            // 而不是把它当成一条全新的完整问题重新首轮路由。
            // 当前 session 处在“等用户澄清”的中间态。
            // 这时不是重新做首轮路由，而是先尝试把用户回答映射回上次给出的候选节点。
            List<IntentNodeDTO> candidates = resolveCandidates(snapshot, pending.getCandidateNodeIds());
            if (candidates.isEmpty()) {
                // 候选节点在最新快照里已经找不到，说明澄清上下文失效。
                // 这里直接返回一个“重新澄清”的 direct reply，并清掉旧 pending。
                pendingIntentResolutionStore.delete(sessionId);
                return TurnPreparationResult.direct(
                        clarificationResponseBuilder.build(List.of(), pending.getParentPath(), true)
                );
            }
            IntentNodeDTO selected = clarificationResolver.resolve(userInput, candidates);
            if (selected == null) {
                // 用户回复仍然不足以确定唯一候选时，继续 direct reply 做二次澄清。
                // 注意这里不会进入 Agent，也不会清掉 pending。
                // 用户回答仍然不足以选中具体节点，继续给出澄清回复，不进入 Agent。
                return TurnPreparationResult.direct(
                        clarificationResponseBuilder.build(candidates, pending.getParentPath(), true)
                );
            }
            pendingIntentResolutionStore.delete(sessionId);
            // 命中澄清候选后，真正要继续路由的 query 不是“这句回答”，
            // 而是上一次被保存在 pending 里的原始问题。
            canonicalQuery = StringUtils.hasText(pending.getOriginalQuery()) ? pending.getOriginalQuery() : userInput.trim();
            // 这里把 selectedNodeId 传给 IntentRouter，表示“从这个已选中的节点继续往下路由”。
            routingResult = intentRouter.route(agentId, canonicalQuery, selected.getId());
        } else {
            // 普通首轮请求走标准路由流程。
            routingResult = intentRouter.route(agentId, canonicalQuery);
        }

        if (routingResult.requiresClarification()) {
            // 这里把本轮澄清候选和原始 query 记录下来，
            // 下一次用户回复时才能走“澄清 continuation”分支。
            // 这一步是整个两轮澄清状态机真正“落盘”的地方。
            pendingIntentResolutionStore.save(PendingIntentResolution.builder()
                    .sessionId(sessionId)
                    .candidateNodeIds(routingResult.clarificationCandidates().stream().map(IntentNodeDTO::getId).toList())
                    .originalQuery(canonicalQuery)
                    .parentPath(routingResult.parentPath())
                    .build());
            return TurnPreparationResult.direct(
                    clarificationResponseBuilder.build(routingResult.clarificationCandidates(), routingResult.parentPath(), false)
            );
        }

        if (!routingResult.hasResolution()) {
            // 没有 resolution 不代表失败，只是这一轮没有可用意图边界。
            // 这种情况下仍然可以把原始 query dispatch 给 Agent。
            return TurnPreparationResult.dispatch(null, canonicalQuery);
        }

        IntentResolution resolution = routingResult.resolution();
        if (resolution.kind() == IntentKind.SYSTEM) {
            // SYSTEM 是编排层可直接终结的结果，不需要进入 Agent runtime。
            return TurnPreparationResult.direct(systemIntentResponseRenderer.render(resolution, canonicalQuery));
        }

        // 非 SYSTEM 的正常意图会携带 resolution 和 rewrite 后的 query 进入 Agent，
        // 这样后续工具筛选、RAG scope 和 prompt 都能利用这个边界。
        return TurnPreparationResult.dispatch(resolution, queryRewriter.rewrite(canonicalQuery, resolution));
    }

    private List<IntentNodeDTO> resolveCandidates(IntentTreeSnapshot snapshot, List<String> candidateNodeIds) {
        // pending 里只保存 nodeId，真正恢复候选节点时要回到最新 snapshot 中查。
        // 这样可以兼容缓存刷新和树结构变更，但也意味着某些旧 pending 可能失效。
        if (snapshot == null || snapshot.isEmpty() || candidateNodeIds == null || candidateNodeIds.isEmpty()) {
            return List.of();
        }
        List<IntentNodeDTO> result = new ArrayList<>();
        for (String candidateNodeId : candidateNodeIds) {
            IntentNodeDTO node = snapshot.findNode(candidateNodeId);
            if (node != null) {
                result.add(node);
            }
        }
        return result;
    }
}
