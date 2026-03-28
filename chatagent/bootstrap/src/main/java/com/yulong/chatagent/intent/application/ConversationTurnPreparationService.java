package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns turn-level routing, clarification continuation, and direct-response branching.
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

    public TurnPreparationResult prepare(String agentId, String sessionId, String userInput) {
        if (!StringUtils.hasText(agentId) || !StringUtils.hasText(sessionId) || !StringUtils.hasText(userInput)) {
            return TurnPreparationResult.passthrough();
        }

        IntentTreeSnapshot snapshot = intentTreeCacheManager.loadActiveSnapshot(agentId);
        if (snapshot.isEmpty()) {
            pendingIntentResolutionStore.delete(sessionId);
            return TurnPreparationResult.passthrough();
        }

        PendingIntentResolution pending = pendingIntentResolutionStore.get(sessionId);
        IntentRoutingResult routingResult;
        String canonicalQuery = userInput.trim();

        if (pending != null) {
            List<IntentNodeDTO> candidates = resolveCandidates(snapshot, pending.getCandidateNodeIds());
            if (candidates.isEmpty()) {
                pendingIntentResolutionStore.delete(sessionId);
                return TurnPreparationResult.direct(
                        clarificationResponseBuilder.build(List.of(), pending.getParentPath(), true)
                );
            }
            IntentNodeDTO selected = clarificationResolver.resolve(userInput, candidates);
            if (selected == null) {
                return TurnPreparationResult.direct(
                        clarificationResponseBuilder.build(candidates, pending.getParentPath(), true)
                );
            }
            pendingIntentResolutionStore.delete(sessionId);
            canonicalQuery = StringUtils.hasText(pending.getOriginalQuery()) ? pending.getOriginalQuery() : userInput.trim();
            routingResult = intentRouter.route(agentId, canonicalQuery, selected.getId());
        } else {
            routingResult = intentRouter.route(agentId, canonicalQuery);
        }

        if (routingResult.requiresClarification()) {
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
            return TurnPreparationResult.dispatch(null, canonicalQuery);
        }

        IntentResolution resolution = routingResult.resolution();
        if (resolution.kind() == IntentKind.SYSTEM) {
            return TurnPreparationResult.direct(systemIntentResponseRenderer.render(resolution, canonicalQuery));
        }

        return TurnPreparationResult.dispatch(resolution, queryRewriter.rewrite(canonicalQuery, resolution));
    }

    private List<IntentNodeDTO> resolveCandidates(IntentTreeSnapshot snapshot, List<String> candidateNodeIds) {
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
