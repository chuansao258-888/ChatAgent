package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.contract.ActionRisk;
import com.yulong.chatagent.agent.runtime.contract.ClarificationKind;
import com.yulong.chatagent.agent.runtime.contract.IntentLabel;
import com.yulong.chatagent.agent.runtime.contract.SourceNeed;
import com.yulong.chatagent.agent.runtime.contract.TurnContractProperties;
import com.yulong.chatagent.agent.runtime.contract.TurnContractEnforcementException;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContractBuilder;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Orchestrates legacy/shadow/enforce intent preparation before Agent dispatch. */
@Component
public class ConversationTurnPreparationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnPreparationService.class);

    private final IntentTreeCacheManager intentTreeCacheManager;
    private final PendingIntentResolutionStore pendingIntentResolutionStore;
    private final ClarificationResolver clarificationResolver;
    private final ClarificationResponseBuilder clarificationResponseBuilder;
    private final IntentRouter intentRouter;
    private final QueryRewriter queryRewriter;
    private final SystemIntentResponseRenderer systemIntentResponseRenderer;
    private final TurnExecutionContractBuilder contractBuilder;
    private final TurnContractProperties contractProperties;
    private final IntentUnderstandingEngine understandingEngine;
    private final IntentPolicyProperties intentPolicyProperties;
    private final IntentSignalAnalyzer signalAnalyzer;
    private final IntentPolicyMetrics metrics;

    public ConversationTurnPreparationService(IntentTreeCacheManager intentTreeCacheManager,
                                              PendingIntentResolutionStore pendingIntentResolutionStore,
                                              ClarificationResolver clarificationResolver,
                                              ClarificationResponseBuilder clarificationResponseBuilder,
                                              IntentRouter intentRouter,
                                              QueryRewriter queryRewriter,
                                              SystemIntentResponseRenderer systemIntentResponseRenderer,
                                              TurnExecutionContractBuilder contractBuilder,
                                              TurnContractProperties contractProperties,
                                              IntentUnderstandingEngine understandingEngine,
                                              IntentPolicyProperties intentPolicyProperties,
                                              IntentSignalAnalyzer signalAnalyzer,
                                              IntentPolicyMetrics metrics) {
        this.intentTreeCacheManager = intentTreeCacheManager;
        this.pendingIntentResolutionStore = pendingIntentResolutionStore;
        this.clarificationResolver = clarificationResolver;
        this.clarificationResponseBuilder = clarificationResponseBuilder;
        this.intentRouter = intentRouter;
        this.queryRewriter = queryRewriter;
        this.systemIntentResponseRenderer = systemIntentResponseRenderer;
        this.contractBuilder = contractBuilder;
        this.contractProperties = contractProperties;
        this.understandingEngine = understandingEngine;
        this.intentPolicyProperties = intentPolicyProperties;
        this.signalAnalyzer = signalAnalyzer;
        this.metrics = metrics;
    }

    /** Compatibility entry for callers without a preassembled visible-history view. */
    public TurnPreparationResult prepare(String agentId, String sessionId, String userInput) {
        return prepare(new TurnPreparationContext(
                agentId, sessionId, userInput, List.of(), false, false,
                null, AgentExecutionMode.REACT));
    }

    public TurnPreparationResult prepare(TurnPreparationContext context) {
        if (context == null || !StringUtils.hasText(context.agentId())
                || !StringUtils.hasText(context.sessionId()) || !StringUtils.hasText(context.userInput())) {
            String input = context == null ? "" : context.userInput();
            return dispatchWithContract(null, input, input, context, null);
        }
        IntentTreeSnapshot snapshot = intentTreeCacheManager.loadActiveSnapshot(context.agentId());
        if (snapshot == null || snapshot.isEmpty()) {
            pendingIntentResolutionStore.delete(context.sessionId());
            return dispatchWithContract(null, context.userInput(), context.userInput(), context, null);
        }
        PendingIntentResolution pending = pendingIntentResolutionStore.get(context.sessionId());
        IntentPolicyMode mode = intentPolicyProperties.getMode();
        if (mode == IntentPolicyMode.LEGACY) {
            return prepareLegacy(context, snapshot, pending, null);
        }
        if (mode == IntentPolicyMode.SHADOW) {
            IntentUnderstandingResult shadow = safeUnderstand(context, snapshot, pending);
            TurnPreparationResult legacy = prepareLegacy(context, snapshot, pending, shadow);
            return legacy;
        }
        return preparePolicy(context, snapshot, pending);
    }

    private TurnPreparationResult prepareLegacy(TurnPreparationContext context,
                                                IntentTreeSnapshot snapshot,
                                                PendingIntentResolution pending,
                                                IntentUnderstandingResult shadow) {
        IntentRoutingResult routingResult;
        String canonicalQuery = context.userInput().trim();
        if (pending != null) {
            List<IntentNodeDTO> candidates = resolveCandidates(snapshot, pending.orderedCandidateNodeIds());
            if (candidates.isEmpty()) {
                pendingIntentResolutionStore.delete(context.sessionId());
                return TurnPreparationResult.direct(clarificationResponseBuilder.build(
                        List.of(), pending.getParentPath(), true, originalOrCurrent(pending, context.userInput())));
            }
            IntentNodeDTO selected = clarificationResolver.resolve(context.userInput(), candidates);
            if (selected == null) {
                if (signalAnalyzer.isSubstantiveContextualTurn(context.userInput())
                        || signalAnalyzer.isExplicitTopicSwitch(context.userInput())) {
                    pendingIntentResolutionStore.delete(context.sessionId());
                    return dispatchWithContract(null, canonicalQuery, context.userInput(), context, shadow);
                }
                return TurnPreparationResult.direct(clarificationResponseBuilder.build(
                        candidates, pending.getParentPath(), true, context.userInput()));
            }
            pendingIntentResolutionStore.delete(context.sessionId());
            canonicalQuery = originalOrCurrent(pending, context.userInput());
            routingResult = intentRouter.route(context.agentId(), canonicalQuery, selected.getId());
        } else {
            routingResult = intentRouter.route(context.agentId(), canonicalQuery);
        }
        recordShadowMismatch(shadow, routingResult);
        if (routingResult.requiresClarification()) {
            if (signalAnalyzer.isSubstantiveContextualTurn(canonicalQuery)) {
                pendingIntentResolutionStore.delete(context.sessionId());
                return dispatchWithContract(null, canonicalQuery, context.userInput(), context, shadow);
            }
            saveLegacyPending(context.sessionId(), canonicalQuery, routingResult);
            return TurnPreparationResult.direct(clarificationResponseBuilder.build(
                    routingResult.clarificationCandidates(), routingResult.parentPath(), false, context.userInput()));
        }
        if (!routingResult.hasResolution()) {
            return dispatchWithContract(null, canonicalQuery, context.userInput(), context, shadow);
        }
        IntentResolution resolution = routingResult.resolution();
        if (resolution.kind() == IntentKind.SYSTEM) {
            return TurnPreparationResult.direct(systemIntentResponseRenderer.render(resolution, canonicalQuery));
        }
        String rewritten = queryRewriter.rewrite(canonicalQuery, resolution);
        return dispatchWithContract(resolution, rewritten, canonicalQuery, context, shadow);
    }

    private TurnPreparationResult preparePolicy(TurnPreparationContext context,
                                                IntentTreeSnapshot snapshot,
                                                PendingIntentResolution pending) {
        if (pending != null) {
            TurnPreparationResult pendingResult = resolvePendingPolicy(context, snapshot, pending);
            if (pendingResult != null) {
                return pendingResult;
            }
        }
        IntentUnderstandingResult understanding = understandingEngine.understand(
                buildRequest(context, snapshot, null, context.userInput()));
        return applyPolicyDecision(context, snapshot, understanding, context.userInput());
    }

    private TurnPreparationResult resolvePendingPolicy(TurnPreparationContext context,
                                                       IntentTreeSnapshot snapshot,
                                                       PendingIntentResolution pending) {
        List<IntentNodeDTO> candidates = resolveCandidates(snapshot, pending.orderedCandidateNodeIds());
        if (candidates.isEmpty()) {
            pendingIntentResolutionStore.delete(context.sessionId());
            metrics.recordClarification("cancelled", ClarificationResolver.ReplyOutcome.UNRESOLVED);
            return TurnPreparationResult.direct(clarificationResponseBuilder.build(
                    List.of(), pending.getParentPath(), true, originalOrCurrent(pending, context.userInput())));
        }
        ClarificationResolver.ClarificationReply reply = clarificationResolver.resolveTyped(
                context.userInput(), candidates);
        metrics.recordClarification("resolved", reply.outcome());
        switch (reply.outcome()) {
            case CANCEL, NONE_OF_THESE -> {
                pendingIntentResolutionStore.delete(context.sessionId());
                metrics.recordClarification("cancelled", reply.outcome());
                return TurnPreparationResult.direct(
                        clarificationResponseBuilder.buildReleased(reply.outcome(), context.userInput()));
            }
            case NEW_TOPIC -> {
                pendingIntentResolutionStore.delete(context.sessionId());
                metrics.recordClarification("topic_switch", reply.outcome());
                String topic = StringUtils.hasText(reply.newTopicText())
                        ? reply.newTopicText() : context.userInput();
                IntentUnderstandingResult fresh = understandingEngine.understand(
                        buildRequest(context, snapshot, null, topic));
                return applyPolicyDecision(context, snapshot, fresh, topic);
            }
            case SELECT_ONE -> {
                pendingIntentResolutionStore.delete(context.sessionId());
                String original = originalOrCurrent(pending, context.userInput());
                return applyPolicyDecision(context, snapshot,
                        explicitSelection(snapshot, reply.selected(), original, false), original);
            }
            case SELECT_MANY -> {
                pendingIntentResolutionStore.delete(context.sessionId());
                String original = originalOrCurrent(pending, context.userInput());
                return applyPolicyDecision(context, snapshot,
                        explicitSelection(snapshot, reply.selected(), original, true), original);
            }
            case UNRESOLVED -> {
                IntentUnderstandingResult fresh = understandingEngine.understand(
                        buildRequest(context, snapshot, null, context.userInput()));
                if (isClearNewTopic(fresh, candidates)) {
                    pendingIntentResolutionStore.delete(context.sessionId());
                    metrics.recordClarification("topic_switch", ClarificationResolver.ReplyOutcome.NEW_TOPIC);
                    return applyPolicyDecision(context, snapshot, fresh, context.userInput());
                }
                int attempt = pending.attemptCountOrZero() + 1;
                if (attempt >= intentPolicyProperties.boundedMaxClarificationAttempts()) {
                    pendingIntentResolutionStore.delete(context.sessionId());
                    metrics.recordClarification("cancelled", reply.outcome());
                    return TurnPreparationResult.direct(
                            clarificationResponseBuilder.buildRetryLimitReached(context.userInput()));
                }
                pending.setAttemptCount(attempt);
                pendingIntentResolutionStore.save(pending);
                metrics.recordClarification("retried", reply.outcome());
                return TurnPreparationResult.direct(clarificationResponseBuilder.build(
                        candidates, pending.getParentPath(), true, context.userInput()));
            }
            default -> throw new IllegalStateException("Unsupported clarification reply outcome");
        }
    }

    private TurnPreparationResult applyPolicyDecision(TurnPreparationContext context,
                                                      IntentTreeSnapshot snapshot,
                                                      IntentUnderstandingResult understanding,
                                                      String canonicalQuery) {
        IntentDecision decision = understanding.decision();
        return switch (decision.outcome()) {
            case GENERAL_CHAT, OUT_OF_DOMAIN -> dispatchWithContract(
                    null, canonicalQuery, canonicalQuery, context, understanding);
            case AMBIGUOUS_ROUTE -> startPolicyClarification(
                    context, snapshot, understanding, canonicalQuery);
            case EXECUTION_INFO_MISSING -> TurnPreparationResult.direct(
                    clarificationResponseBuilder.buildExecutionInfoMissing(
                            decision.missingDimensions(), canonicalQuery));
            case KNOWN_INTENT -> dispatchKnown(
                    context, snapshot, understanding, canonicalQuery, true);
            case MULTI_INTENT -> dispatchKnown(
                    context, snapshot, understanding, canonicalQuery, false);
        };
    }

    private TurnPreparationResult startPolicyClarification(TurnPreparationContext context,
                                                           IntentTreeSnapshot snapshot,
                                                           IntentUnderstandingResult understanding,
                                                           String canonicalQuery) {
        List<IntentNodeDTO> candidates = resolveCandidates(snapshot,
                understanding.decision().rankedCandidates().stream()
                        .map(IntentCandidateEvidence::nodeId).toList());
        if (candidates.isEmpty()) {
            return dispatchWithContract(null, canonicalQuery, canonicalQuery, context, understanding);
        }
        List<PendingIntentResolution.PendingCandidate> ordered = new ArrayList<>();
        for (int i = 0; i < understanding.decision().rankedCandidates().size(); i++) {
            IntentCandidateEvidence evidence = understanding.decision().rankedCandidates().get(i);
            ordered.add(new PendingIntentResolution.PendingCandidate(
                    evidence.nodeId(), evidence.lexicalScore(), i + 1));
        }
        pendingIntentResolutionStore.save(PendingIntentResolution.builder()
                .sessionId(context.sessionId())
                .candidateNodeIds(candidates.stream().map(IntentNodeDTO::getId).toList())
                .orderedCandidates(ordered)
                .originalQuery(canonicalQuery)
                .parentPath(commonParentPath(snapshot, candidates))
                .clarificationKind(ClarificationKind.ROUTE_CHOICE)
                .attemptCount(0)
                .policyProfileVersion(understanding.decision().policyProfileVersion())
                .contractVersion(TurnExecutionContract.VERSION)
                .build());
        metrics.recordClarification("started", null);
        return TurnPreparationResult.direct(clarificationResponseBuilder.build(
                candidates, commonParentPath(snapshot, candidates), false, canonicalQuery));
    }

    private TurnPreparationResult dispatchKnown(TurnPreparationContext context,
                                                IntentTreeSnapshot snapshot,
                                                IntentUnderstandingResult understanding,
                                                String canonicalQuery,
                                                boolean rewrite) {
        List<IntentResolution> resolutions = resolveOrderedRoutes(snapshot, understanding.decision());
        IntentResolution resolution = resolutions.isEmpty() ? null : resolutions.get(0);
        if (resolution == null) {
            if (understanding.decision().outcome() == IntentRouteOutcome.MULTI_INTENT) {
                return TurnPreparationResult.direct(
                        clarificationResponseBuilder.buildMultiIntentConflict(canonicalQuery));
            }
            return dispatchWithContract(null, canonicalQuery, canonicalQuery, context, understanding);
        }
        if (understanding.decision().outcome() == IntentRouteOutcome.MULTI_INTENT) {
            if (understanding.actionRisk() != ActionRisk.READ_ONLY) {
                return TurnPreparationResult.direct(
                        clarificationResponseBuilder.buildExecutionInfoMissing(
                                List.of(MissingDimension.CONFIRMATION), canonicalQuery));
            }
            if (!compatibleReadOnly(resolutions)) {
                return TurnPreparationResult.direct(
                        clarificationResponseBuilder.buildMultiIntentConflict(canonicalQuery));
            }
        }
        if (resolution.kind() == IntentKind.SYSTEM
                && understanding.decision().outcome() == IntentRouteOutcome.KNOWN_INTENT) {
            return TurnPreparationResult.direct(systemIntentResponseRenderer.render(resolution, canonicalQuery));
        }
        String dispatched = rewrite ? queryRewriter.rewrite(canonicalQuery, resolution) : canonicalQuery;
        return dispatchRoutesWithContract(resolutions, dispatched, canonicalQuery, context, understanding);
    }

    private List<IntentResolution> resolveOrderedRoutes(IntentTreeSnapshot snapshot,
                                                        IntentDecision decision) {
        List<String> routeIds = new ArrayList<>();
        if (StringUtils.hasText(decision.primaryNodeId())) {
            routeIds.add(decision.primaryNodeId());
        }
        if (decision.outcome() == IntentRouteOutcome.MULTI_INTENT) {
            routeIds.addAll(decision.secondaryNodeIds());
        }
        if (routeIds.isEmpty() || new LinkedHashSet<>(routeIds).size() != routeIds.size()) {
            return List.of();
        }
        List<IntentResolution> resolutions = new ArrayList<>(routeIds.size());
        for (String routeId : routeIds) {
            IntentResolution resolved = snapshot.resolveNode(
                    routeId, intentPolicyProperties.isKbInheritanceEnabled());
            if (resolved == null) {
                return List.of();
            }
            resolutions.add(resolved);
        }
        return List.copyOf(resolutions);
    }

    private IntentUnderstandingResult explicitSelection(IntentTreeSnapshot snapshot,
                                                        List<IntentNodeDTO> selected,
                                                        String originalQuery,
                                                        boolean multi) {
        IntentNodeDTO primary = selected.get(0);
        List<String> secondaryIds = multi
                ? selected.subList(1, selected.size()).stream().map(IntentNodeDTO::getId).toList()
                : List.of();
        List<IntentCandidateEvidence> evidence = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            IntentNodeDTO node = selected.get(i);
            String path = snapshot.pathTo(node.getId()).stream().map(IntentNodeDTO::getName)
                    .filter(StringUtils::hasText).reduce((left, right) -> left + " > " + right).orElse("");
            evidence.add(new IntentCandidateEvidence(node.getId(), path, 0.0d, 0.0d,
                    i + 1, List.of("explicit_clarification_selection")));
        }
        IntentDecision decision = new IntentDecision(
                multi ? IntentRouteOutcome.MULTI_INTENT : IntentRouteOutcome.KNOWN_INTENT,
                primary.getId(), secondaryIds, evidence, List.of(),
                IntentDecisionSource.DETERMINISTIC, 0.0d, ConfidenceStatus.UNCALIBRATED,
                intentPolicyProperties.getProfileVersion(), List.of("clarification_recovered"));
        IntentSignalAnalyzer.IntentTurnSignals signals = signalAnalyzer.analyze(originalQuery);
        SourceNeed sourceNeed = signalAnalyzer.deriveSourceNeed(signals, primary.getIntentKind());
        List<IntentLabel> labels = new ArrayList<>(signals.secondaryIntents());
        if (multi) {
            labels.add(IntentLabel.MULTI_INTENT);
        }
        return new IntentUnderstandingResult(decision, sourceNeed, signals.timeSensitivity(),
                signals.actionRisk(), List.copyOf(new LinkedHashSet<>(labels)), false, false);
    }

    private boolean compatibleReadOnly(List<IntentResolution> resolutions) {
        return resolutions != null && resolutions.size() > 1 && resolutions.stream()
                .allMatch(resolution -> resolution != null
                        && resolution.kind() == IntentKind.KB
                        && resolution.allowedTools().isEmpty());
    }

    private boolean isClearNewTopic(IntentUnderstandingResult result, List<IntentNodeDTO> pendingCandidates) {
        if (result == null) {
            return false;
        }
        if (result.decision().outcome() == IntentRouteOutcome.GENERAL_CHAT
                || result.decision().outcome() == IntentRouteOutcome.OUT_OF_DOMAIN) {
            return true;
        }
        if (!result.decision().hasPrimaryNode()) {
            return false;
        }
        return pendingCandidates.stream().noneMatch(
                candidate -> candidate.getId().equals(result.decision().primaryNodeId()));
    }

    private IntentUnderstandingResult safeUnderstand(TurnPreparationContext context,
                                                     IntentTreeSnapshot snapshot,
                                                     PendingIntentResolution pending) {
        try {
            return understandingEngine.understand(
                    buildRequest(context, snapshot, pending, context.userInput()));
        } catch (RuntimeException exception) {
            log.warn("Shadow intent understanding failed: reason={}", exception.getClass().getSimpleName());
            return null;
        }
    }

    private IntentUnderstandingRequest buildRequest(TurnPreparationContext context,
                                                    IntentTreeSnapshot snapshot,
                                                    PendingIntentResolution pending,
                                                    String userInput) {
        return new IntentUnderstandingRequest(
                context.agentId(), context.sessionId(), userInput,
                context.recentVisibleTurns(), context.contextAvailable(), context.contextTruncated(),
                pending, snapshot, context.sessionAssetSummary(), context.executionMode());
    }

    private void saveLegacyPending(String sessionId,
                                   String canonicalQuery,
                                   IntentRoutingResult routingResult) {
        pendingIntentResolutionStore.save(PendingIntentResolution.builder()
                .sessionId(sessionId)
                .candidateNodeIds(routingResult.clarificationCandidates().stream()
                        .map(IntentNodeDTO::getId).toList())
                .originalQuery(canonicalQuery)
                .parentPath(routingResult.parentPath())
                .clarificationKind(ClarificationKind.ROUTE_CHOICE)
                .attemptCount(0)
                .policyProfileVersion("legacy")
                .contractVersion(TurnExecutionContract.VERSION)
                .build());
    }

    private void recordShadowMismatch(IntentUnderstandingResult shadow,
                                      IntentRoutingResult legacy) {
        if (shadow == null) {
            return;
        }
        boolean mismatch;
        if (legacy.requiresClarification()) {
            mismatch = shadow.decision().outcome() != IntentRouteOutcome.AMBIGUOUS_ROUTE;
        } else if (legacy.hasResolution()) {
            String legacyNodeId = legacy.resolution().path().isEmpty() ? null
                    : legacy.resolution().path().get(legacy.resolution().path().size() - 1).getId();
            mismatch = shadow.decision().outcome() != IntentRouteOutcome.KNOWN_INTENT
                    || !java.util.Objects.equals(legacyNodeId, shadow.decision().primaryNodeId());
        } else {
            mismatch = !shadow.decision().isPassThrough();
        }
        metrics.recordShadowMismatch(mismatch);
    }

    private TurnPreparationResult dispatchWithContract(IntentResolution resolution,
                                                       String dispatchedInput,
                                                       String originalUserInput,
                                                       TurnPreparationContext context,
                                                       IntentUnderstandingResult understanding) {
        List<IntentResolution> resolutions = resolution == null ? List.of() : List.of(resolution);
        return dispatchRoutesWithContract(resolutions, dispatchedInput, originalUserInput, context, understanding);
    }

    private TurnPreparationResult dispatchRoutesWithContract(List<IntentResolution> resolutions,
                                                             String dispatchedInput,
                                                             String originalUserInput,
                                                             TurnPreparationContext context,
                                                             IntentUnderstandingResult understanding) {
        IntentResolution primaryResolution = resolutions == null || resolutions.isEmpty()
                ? null : resolutions.get(0);
        if (!contractProperties.isEnabled()) {
            return TurnPreparationResult.dispatch(primaryResolution, dispatchedInput);
        }
        AgentExecutionMode mode = context == null ? AgentExecutionMode.REACT : context.executionMode();
        TurnExecutionContract contract;
        try {
            contract = contractBuilder.buildForRoutes(
                    resolutions, originalUserInput, dispatchedInput, mode, understanding);
        } catch (RuntimeException exception) {
            if (contractProperties.isRetrievalEnforced()) {
                throw new TurnContractEnforcementException(
                        "Retrieval turn contract construction failed in enforce mode",
                        exception);
            }
            log.warn("Turn contract build failed, dispatching without contract: reason={}",
                    exception.getClass().getSimpleName());
            return TurnPreparationResult.dispatch(primaryResolution, dispatchedInput);
        }
        if (contract == null) {
            if (contractProperties.isRetrievalEnforced()) {
                throw new TurnContractEnforcementException(
                        "Retrieval turn contract builder returned null in enforce mode");
            }
            log.warn("Turn contract builder returned null, dispatching without contract");
            return TurnPreparationResult.dispatch(primaryResolution, dispatchedInput);
        }
        if (contractProperties.isEmitDebugMetadata()) {
            log.info("Turn contract built: routeOutcome={}, intentKind={}, sourceNeed={}, retrievalMode={}, enforcement={}",
                    contract.analysis().intentDecision() == null
                            ? "LEGACY" : contract.analysis().intentDecision().outcome(),
                    contract.intent().kind(), contract.analysis().sourceNeed(),
                    contract.retrieval().mode(), contractProperties.getRetrievalEnforcement());
        }
        return TurnPreparationResult.dispatch(primaryResolution, dispatchedInput, contract);
    }

    private List<IntentNodeDTO> resolveCandidates(IntentTreeSnapshot snapshot, List<String> candidateNodeIds) {
        if (snapshot == null || snapshot.isEmpty() || candidateNodeIds == null) {
            return List.of();
        }
        List<IntentNodeDTO> result = new ArrayList<>();
        for (String nodeId : candidateNodeIds) {
            IntentNodeDTO node = snapshot.findNode(nodeId);
            if (node != null && result.stream().noneMatch(existing -> existing.getId().equals(node.getId()))) {
                result.add(node);
            }
        }
        return List.copyOf(result);
    }

    private String originalOrCurrent(PendingIntentResolution pending, String current) {
        return StringUtils.hasText(pending.getOriginalQuery()) ? pending.getOriginalQuery() : current.trim();
    }

    private String commonParentPath(IntentTreeSnapshot snapshot, List<IntentNodeDTO> candidates) {
        if (candidates.isEmpty()) {
            return "";
        }
        String parentId = candidates.get(0).getParentId();
        boolean sameParent = candidates.stream().allMatch(
                candidate -> java.util.Objects.equals(parentId, candidate.getParentId()));
        if (!sameParent || parentId == null) {
            return "";
        }
        return snapshot.pathTo(parentId).stream().map(IntentNodeDTO::getName)
                .filter(StringUtils::hasText).reduce((left, right) -> left + " > " + right).orElse("");
    }
}
