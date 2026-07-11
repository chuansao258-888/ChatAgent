package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.contract.ActionRisk;
import com.yulong.chatagent.agent.runtime.contract.IntentLabel;
import com.yulong.chatagent.agent.runtime.contract.SourceNeed;
import com.yulong.chatagent.agent.runtime.contract.SourceReferenceClassifier;
import com.yulong.chatagent.agent.runtime.contract.TimeSensitivity;
import com.yulong.chatagent.agent.runtime.contract.TurnContractProperties;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContractBuilder;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationTurnPreparationPolicyTest {

    @Mock private IntentTreeCacheManager treeCacheManager;
    @Mock private PendingIntentResolutionStore pendingStore;
    @Mock private ClarificationResolver clarificationResolver;
    @Mock private ClarificationResponseBuilder responseBuilder;
    @Mock private IntentRouter legacyRouter;
    @Mock private QueryRewriter queryRewriter;
    @Mock private SystemIntentResponseRenderer systemRenderer;
    @Mock private TurnExecutionContractBuilder contractBuilder;
    @Mock private TurnExecutionContract executionContract;
    @Mock private IntentUnderstandingEngine understandingEngine;
    @Mock private IntentPolicyMetrics metrics;

    private final TurnContractProperties contractProperties = new TurnContractProperties();
    private final IntentPolicyProperties policyProperties = new IntentPolicyProperties();
    private final IntentSignalAnalyzer signalAnalyzer =
            new IntentSignalAnalyzer(new SourceReferenceClassifier());
    private IntentTreeSnapshot snapshot;

    @BeforeEach
    void setUp() {
        contractProperties.setEnabled(false);
        snapshot = new IntentTreeSnapshot("agent", 1, List.of(
                leaf("leave", "Annual Leave"),
                leaf("expense", "Travel Expense")), Map.of());
        when(treeCacheManager.loadActiveSnapshot("agent")).thenReturn(snapshot);
    }

    @Test
    void shouldReachAllSixOutcomesThroughPublicPreparationPath() {
        ConversationTurnPreparationService service = service(IntentPolicyMode.ENFORCE);
        when(queryRewriter.rewrite(anyString(), any())).thenReturn("rewritten leave query");
        when(responseBuilder.build(any(), anyString(), anyBoolean(), anyString())).thenReturn("choose");
        when(responseBuilder.buildExecutionInfoMissing(any(), anyString())).thenReturn("confirm");
        when(understandingEngine.understand(any())).thenReturn(
                result(IntentRouteOutcome.KNOWN_INTENT),
                result(IntentRouteOutcome.GENERAL_CHAT),
                result(IntentRouteOutcome.OUT_OF_DOMAIN),
                result(IntentRouteOutcome.AMBIGUOUS_ROUTE),
                result(IntentRouteOutcome.EXECUTION_INFO_MISSING),
                result(IntentRouteOutcome.MULTI_INTENT));

        TurnPreparationResult known = service.prepare(context("annual leave process"));
        TurnPreparationResult general = service.prepare(context("hello there"));
        TurnPreparationResult ood = service.prepare(context("football result"));
        TurnPreparationResult ambiguous = service.prepare(context("expense"));
        TurnPreparationResult missing = service.prepare(context("send the payroll email"));
        TurnPreparationResult multi = service.prepare(context("leave and expense"));

        assertThat(known.isDirectReply()).isFalse();
        assertThat(known.intentResolution().pathLabel()).isEqualTo("Annual Leave");
        assertThat(known.rewrittenInput()).isEqualTo("rewritten leave query");
        assertThat(general.isDirectReply()).isFalse();
        assertThat(general.intentResolution()).isNull();
        assertThat(ood.isDirectReply()).isFalse();
        assertThat(ood.intentResolution()).isNull();
        assertThat(ambiguous.directReply()).isEqualTo("choose");
        assertThat(missing.directReply()).isEqualTo("confirm");
        assertThat(multi.isDirectReply()).isFalse();
        assertThat(multi.intentResolution().pathLabel()).isEqualTo("Annual Leave");
        assertThat(multi.rewrittenInput()).isEqualTo("leave and expense");

        ArgumentCaptor<PendingIntentResolution> pendingCaptor =
                ArgumentCaptor.forClass(PendingIntentResolution.class);
        verify(pendingStore).save(pendingCaptor.capture());
        assertThat(pendingCaptor.getValue().orderedCandidateNodeIds())
                .containsExactly("leave", "expense");
        assertThat(pendingCaptor.getValue().getPolicyProfileVersion()).isEqualTo("v1");
    }

    @Test
    void shouldKeepLegacyDecisionUserVisibleInShadowMode() {
        ConversationTurnPreparationService service = service(IntentPolicyMode.SHADOW);
        IntentResolution legacyResolution = snapshot.resolveNode("leave");
        when(understandingEngine.understand(any())).thenReturn(result(IntentRouteOutcome.GENERAL_CHAT));
        when(legacyRouter.route("agent", "annual leave process"))
                .thenReturn(IntentRoutingResult.resolved(legacyResolution));
        when(queryRewriter.rewrite("annual leave process", legacyResolution)).thenReturn("legacy rewrite");

        TurnPreparationResult prepared = service.prepare(context("annual leave process"));

        assertThat(prepared.isDirectReply()).isFalse();
        assertThat(prepared.intentResolution()).isEqualTo(legacyResolution);
        assertThat(prepared.rewrittenInput()).isEqualTo("legacy rewrite");
        verify(metrics).recordShadowMismatch(true);
    }

    @Test
    void shouldRecoverCompatibleSelectManyAndClearPending() {
        contractProperties.setEnabled(true);
        contractProperties.setRetrievalEnforcement("warn");
        ConversationTurnPreparationService service = service(IntentPolicyMode.ENFORCE);
        PendingIntentResolution pending = pending(0);
        List<IntentNodeDTO> candidates = snapshot.getNodes();
        when(pendingStore.get("session")).thenReturn(pending);
        when(clarificationResolver.resolveTyped("both", candidates)).thenReturn(
                ClarificationResolver.ClarificationReply.selected(
                        ClarificationResolver.ReplyOutcome.SELECT_MANY, candidates));
        when(contractBuilder.buildForRoutes(anyList(), anyString(), anyString(), any(), any()))
                .thenReturn(executionContract);

        TurnPreparationResult prepared = service.prepare(context("both"));

        assertThat(prepared.isDirectReply()).isFalse();
        assertThat(prepared.intentResolution().pathLabel()).isEqualTo("Annual Leave");
        assertThat(prepared.rewrittenInput()).isEqualTo("leave and expense");
        assertThat(prepared.executionContract()).isSameAs(executionContract);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IntentResolution>> routesCaptor = ArgumentCaptor.forClass(List.class);
        verify(contractBuilder).buildForRoutes(routesCaptor.capture(), anyString(), anyString(), any(), any());
        assertThat(routesCaptor.getValue()).extracting(IntentResolution::pathLabel)
                .containsExactly("Annual Leave", "Travel Expense");
        verify(pendingStore).delete("session");
        verify(understandingEngine, never()).understand(any());
    }

    @Test
    void shouldPreserveAutomaticMultiIntentRouteScopeAndFallbackOrder() {
        contractProperties.setEnabled(true);
        contractProperties.setRetrievalEnforcement("warn");
        IntentNodeDTO strict = leaf("leave", "Annual Leave");
        strict.setScopePolicy(ScopePolicy.STRICT);
        IntentNodeDTO fallback = leaf("expense", "Travel Expense");
        fallback.setScopePolicy(ScopePolicy.FALLBACK_ALLOWED);
        snapshot = new IntentTreeSnapshot("agent", 1, List.of(strict, fallback),
                Map.of("leave", List.of("kb-a"), "expense", List.of("kb-b")));
        when(treeCacheManager.loadActiveSnapshot("agent")).thenReturn(snapshot);
        when(understandingEngine.understand(any())).thenReturn(result(IntentRouteOutcome.MULTI_INTENT));
        when(contractBuilder.buildForRoutes(anyList(), anyString(), anyString(), any(), any()))
                .thenReturn(executionContract);
        ConversationTurnPreparationService service = service(IntentPolicyMode.ENFORCE);

        TurnPreparationResult prepared = service.prepare(context("leave and expense"));

        assertThat(prepared.executionContract()).isSameAs(executionContract);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IntentResolution>> routesCaptor = ArgumentCaptor.forClass(List.class);
        verify(contractBuilder).buildForRoutes(routesCaptor.capture(), anyString(), anyString(), any(), any());
        assertThat(routesCaptor.getValue()).hasSize(2);
        assertThat(routesCaptor.getValue().get(0).scopedKbIds()).containsExactly("kb-a");
        assertThat(routesCaptor.getValue().get(0).scopePolicy()).isEqualTo(ScopePolicy.STRICT);
        assertThat(routesCaptor.getValue().get(1).scopedKbIds()).containsExactly("kb-b");
        assertThat(routesCaptor.getValue().get(1).scopePolicy()).isEqualTo(ScopePolicy.FALLBACK_ALLOWED);
    }

    @Test
    void shouldClarifyIncompatibleAutomaticMultiIntentBeforeDispatch() {
        IntentNodeDTO kb = leaf("leave", "Annual Leave");
        IntentNodeDTO tool = leaf("expense", "Expense Submission");
        tool.setIntentKind(IntentKind.TOOL);
        tool.setAllowedTools(List.of("submitExpense"));
        snapshot = new IntentTreeSnapshot("agent", 1, List.of(kb, tool), Map.of());
        when(treeCacheManager.loadActiveSnapshot("agent")).thenReturn(snapshot);
        when(understandingEngine.understand(any())).thenReturn(result(IntentRouteOutcome.MULTI_INTENT));
        when(responseBuilder.buildMultiIntentConflict("leave and expense")).thenReturn("conflict");
        ConversationTurnPreparationService service = service(IntentPolicyMode.ENFORCE);

        TurnPreparationResult prepared = service.prepare(context("leave and expense"));

        assertThat(prepared.directReply()).isEqualTo("conflict");
        verify(contractBuilder, never()).buildForRoutes(anyList(), anyString(), anyString(), any(), any());
    }

    @Test
    void shouldRequireConfirmationForSideEffectingMultiIntentBeforeDispatch() {
        IntentUnderstandingResult base = result(IntentRouteOutcome.MULTI_INTENT);
        IntentUnderstandingResult risky = new IntentUnderstandingResult(
                base.decision(), base.sourceNeed(), base.timeSensitivity(), ActionRisk.WRITE_ACTION,
                base.secondaryIntents(), base.contextUsed(), base.contextTruncated());
        when(understandingEngine.understand(any())).thenReturn(risky);
        when(responseBuilder.buildExecutionInfoMissing(
                List.of(MissingDimension.CONFIRMATION), "leave and expense")).thenReturn("confirm");
        ConversationTurnPreparationService service = service(IntentPolicyMode.ENFORCE);

        TurnPreparationResult prepared = service.prepare(context("leave and expense"));

        assertThat(prepared.directReply()).isEqualTo("confirm");
        verify(contractBuilder, never()).buildForRoutes(anyList(), anyString(), anyString(), any(), any());
    }

    @Test
    void shouldFailClosedWhenAutomaticMultiIntentSecondaryRouteIsMissing() {
        snapshot = new IntentTreeSnapshot("agent", 1, List.of(leaf("leave", "Annual Leave")), Map.of());
        when(treeCacheManager.loadActiveSnapshot("agent")).thenReturn(snapshot);
        when(understandingEngine.understand(any())).thenReturn(result(IntentRouteOutcome.MULTI_INTENT));
        when(responseBuilder.buildMultiIntentConflict("leave and expense")).thenReturn("conflict");
        ConversationTurnPreparationService service = service(IntentPolicyMode.ENFORCE);

        TurnPreparationResult prepared = service.prepare(context("leave and expense"));

        assertThat(prepared.directReply()).isEqualTo("conflict");
        verify(contractBuilder, never()).buildForRoutes(anyList(), anyString(), anyString(), any(), any());
    }

    @Test
    void shouldReleasePendingAtRetryLimit() {
        ConversationTurnPreparationService service = service(IntentPolicyMode.ENFORCE);
        PendingIntentResolution pending = pending(1);
        List<IntentNodeDTO> candidates = snapshot.getNodes();
        when(pendingStore.get("session")).thenReturn(pending);
        when(clarificationResolver.resolveTyped("not sure", candidates))
                .thenReturn(ClarificationResolver.ClarificationReply.unresolved());
        when(understandingEngine.understand(any())).thenReturn(result(IntentRouteOutcome.AMBIGUOUS_ROUTE));
        when(responseBuilder.buildRetryLimitReached("not sure")).thenReturn("released");

        TurnPreparationResult prepared = service.prepare(context("not sure"));

        assertThat(prepared.directReply()).isEqualTo("released");
        verify(pendingStore).delete("session");
        verify(pendingStore, never()).save(any());
    }

    @Test
    void shouldClearPendingAndClassifyExplicitNewTopicImmediately() {
        ConversationTurnPreparationService service = service(IntentPolicyMode.ENFORCE);
        List<IntentNodeDTO> candidates = snapshot.getNodes();
        when(pendingStore.get("session")).thenReturn(pending(0));
        when(clarificationResolver.resolveTyped("Actually, tell me a joke", candidates)).thenReturn(
                ClarificationResolver.ClarificationReply.newTopic("tell me a joke"));
        when(understandingEngine.understand(any())).thenReturn(result(IntentRouteOutcome.GENERAL_CHAT));

        TurnPreparationResult prepared = service.prepare(context("Actually, tell me a joke"));

        assertThat(prepared.isDirectReply()).isFalse();
        assertThat(prepared.rewrittenInput()).isEqualTo("tell me a joke");
        verify(pendingStore).delete("session");
    }

    @Test
    void shouldClearPendingForCancelWithoutReclassification() {
        ConversationTurnPreparationService service = service(IntentPolicyMode.ENFORCE);
        List<IntentNodeDTO> candidates = snapshot.getNodes();
        when(pendingStore.get("session")).thenReturn(pending(0));
        when(clarificationResolver.resolveTyped("cancel", candidates)).thenReturn(
                ClarificationResolver.ClarificationReply.of(ClarificationResolver.ReplyOutcome.CANCEL));
        when(responseBuilder.buildReleased(ClarificationResolver.ReplyOutcome.CANCEL, "cancel"))
                .thenReturn("cancelled");

        TurnPreparationResult prepared = service.prepare(context("cancel"));

        assertThat(prepared.directReply()).isEqualTo("cancelled");
        verify(pendingStore).delete("session");
        verify(understandingEngine, never()).understand(any());
    }

    private ConversationTurnPreparationService service(IntentPolicyMode mode) {
        policyProperties.setMode(mode);
        policyProperties.setMaxClarificationAttempts(2);
        return new ConversationTurnPreparationService(
                treeCacheManager, pendingStore, clarificationResolver, responseBuilder,
                legacyRouter, queryRewriter, systemRenderer, contractBuilder, contractProperties,
                understandingEngine, policyProperties, signalAnalyzer, metrics);
    }

    private TurnPreparationContext context(String text) {
        return new TurnPreparationContext(
                "agent", "session", text, List.of(), true, false,
                null, AgentExecutionMode.REACT);
    }

    private PendingIntentResolution pending(int attempts) {
        return PendingIntentResolution.builder()
                .sessionId("session")
                .candidateNodeIds(List.of("leave", "expense"))
                .originalQuery("leave and expense")
                .attemptCount(attempts)
                .build();
    }

    private IntentUnderstandingResult result(IntentRouteOutcome outcome) {
        String primary = switch (outcome) {
            case KNOWN_INTENT, EXECUTION_INFO_MISSING, MULTI_INTENT -> "leave";
            default -> null;
        };
        List<String> secondary = outcome == IntentRouteOutcome.MULTI_INTENT
                ? List.of("expense") : List.of();
        List<IntentCandidateEvidence> evidence = switch (outcome) {
            case AMBIGUOUS_ROUTE, MULTI_INTENT -> List.of(
                    evidence("leave", "Annual Leave", 1),
                    evidence("expense", "Travel Expense", 2));
            case KNOWN_INTENT, EXECUTION_INFO_MISSING -> List.of(
                    evidence("leave", "Annual Leave", 1));
            default -> List.of();
        };
        List<MissingDimension> missing = outcome == IntentRouteOutcome.EXECUTION_INFO_MISSING
                ? List.of(MissingDimension.CONFIRMATION) : List.of();
        IntentDecision decision = new IntentDecision(
                outcome, primary, secondary, evidence, missing,
                IntentDecisionSource.CLASSIFIER,
                outcome == IntentRouteOutcome.AMBIGUOUS_ROUTE
                        || outcome == IntentRouteOutcome.EXECUTION_INFO_MISSING ? 0.0d : 0.94d,
                outcome == IntentRouteOutcome.AMBIGUOUS_ROUTE
                        || outcome == IntentRouteOutcome.EXECUTION_INFO_MISSING
                        ? ConfidenceStatus.UNCALIBRATED : ConfidenceStatus.CALIBRATED,
                "v1", List.of("test_fixture"));
        return new IntentUnderstandingResult(
                decision,
                primary == null ? SourceNeed.NONE : SourceNeed.KB,
                TimeSensitivity.STATIC,
                outcome == IntentRouteOutcome.EXECUTION_INFO_MISSING
                        ? ActionRisk.EXTERNAL_SIDE_EFFECT : ActionRisk.READ_ONLY,
                outcome == IntentRouteOutcome.MULTI_INTENT
                        ? List.of(IntentLabel.MULTI_INTENT) : List.of(),
                false,
                false);
    }

    private IntentCandidateEvidence evidence(String id, String path, int rank) {
        return new IntentCandidateEvidence(id, path, 1.0d, 0.1d, rank, List.of("semantic_match"));
    }

    private IntentNodeDTO leaf(String id, String name) {
        return IntentNodeDTO.builder()
                .id(id)
                .name(name)
                .description(name + " description")
                .examples(List.of(name + " details", name + " policy"))
                .intentKind(IntentKind.KB)
                .enabled(true)
                .build();
    }
}
