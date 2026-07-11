package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.contract.ActionRisk;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntentUnderstandingEngineTest {

    private IntentPolicyProperties properties;
    private StructuredIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        properties = new IntentPolicyProperties();
        properties.setMode(IntentPolicyMode.ENFORCE);
        classifier = mock(StructuredIntentClassifier.class);
    }

    @Test
    void shouldFastAcceptOnlyUniqueReviewedExactMatch() {
        IntentUnderstandingEngine engine = IntentPolicyTestSupport.engine(properties, classifier);

        IntentUnderstandingResult result = engine.understand(request(
                "Annual Leave", List.of(), snapshot(leaf("leave", "Annual Leave"))));

        assertThat(result.decision().outcome()).isEqualTo(IntentRouteOutcome.KNOWN_INTENT);
        assertThat(result.decision().decisionSource()).isEqualTo(IntentDecisionSource.DETERMINISTIC);
        verifyNoInteractions(classifier);
    }

    @Test
    void shouldExposeEveryClassifierOutcomeWithoutInventingIds() {
        IntentTreeSnapshot snapshot = snapshot(leaf("leave", "Annual Leave"), leaf("expense", "Travel Expense"));
        for (IntentRouteOutcome outcome : IntentRouteOutcome.values()) {
            StructuredIntentClassifier.Result classifierResult = switch (outcome) {
                case KNOWN_INTENT -> success(outcome, "leave", List.of(), List.of("leave"), List.of());
                case MULTI_INTENT -> success(outcome, "leave", List.of("expense"),
                        List.of("leave", "expense"), List.of());
                case AMBIGUOUS_ROUTE -> success(outcome, null, List.of(),
                        List.of("leave", "expense"), List.of());
                case EXECUTION_INFO_MISSING -> success(outcome, "leave", List.of(),
                        List.of("leave"), List.of(MissingDimension.SOURCE));
                case GENERAL_CHAT, OUT_OF_DOMAIN -> success(outcome, null, List.of(), List.of(), List.of());
            };
            when(classifier.classify(any(), anyList())).thenReturn(classifierResult);
            IntentUnderstandingResult result = IntentPolicyTestSupport.engine(properties, classifier)
                    .understand(request("a request without an exact reviewed alias", List.of(), snapshot));
            assertThat(result.decision().outcome()).isEqualTo(outcome);
        }
    }

    @Test
    void shouldUseBoundedVisibleContextForPronounContinuation() {
        IntentTreeSnapshot snapshot = snapshot(leaf("leave", "Annual Leave"));
        when(classifier.classify(any(), anyList())).thenReturn(
                success(IntentRouteOutcome.KNOWN_INTENT, "leave", List.of(), List.of("leave"), List.of()));

        IntentUnderstandingResult result = IntentPolicyTestSupport.engine(properties, classifier)
                .understand(request("what about it?", List.of(
                        new IntentUnderstandingRequest.RecentTurn("user", "Tell me about Annual Leave"),
                        new IntentUnderstandingRequest.RecentTurn("assistant", "It covers paid vacation")
                ), snapshot));

        assertThat(result.contextUsed()).isTrue();
        assertThat(result.decision().reasonCodes()).contains("context_continuation");
    }

    @Test
    void shouldLetExplicitTopicSwitchOverrideStaleContext() {
        IntentTreeSnapshot snapshot = snapshot(
                leaf("leave", "Annual Leave"), leaf("expense", "Travel Expense"));
        when(classifier.classify(any(), anyList())).thenReturn(
                success(IntentRouteOutcome.KNOWN_INTENT, "expense", List.of(), List.of("expense"), List.of()));

        IntentUnderstandingResult result = IntentPolicyTestSupport.engine(properties, classifier)
                .understand(request("Actually, Travel Expense", List.of(
                        new IntentUnderstandingRequest.RecentTurn("user", "Annual Leave")), snapshot));

        assertThat(result.contextUsed()).isFalse();
        assertThat(result.decision().primaryNodeId()).isEqualTo("expense");
    }

    @Test
    void shouldTreatExplicitCorrectionAsCurrentTopicAuthority() {
        IntentTreeSnapshot snapshot = snapshot(
                leaf("leave", "Annual Leave"), leaf("expense", "Travel Expense"));
        when(classifier.classify(any(), anyList())).thenReturn(
                success(IntentRouteOutcome.KNOWN_INTENT, "expense", List.of(), List.of("expense"), List.of()));

        IntentUnderstandingResult result = IntentPolicyTestSupport.engine(properties, classifier)
                .understand(request("No, I mean Travel Expense", List.of(
                        new IntentUnderstandingRequest.RecentTurn("user", "Annual Leave")), snapshot));

        assertThat(result.contextUsed()).isFalse();
        assertThat(result.decision().primaryNodeId()).isEqualTo("expense");
    }

    @Test
    void shouldNeverAutoRouteUnconfirmedExternalAction() {
        IntentTreeSnapshot snapshot = snapshot(toolLeaf("send", "Send Payroll Email"));
        when(classifier.classify(any(), anyList())).thenReturn(
                success(IntentRouteOutcome.KNOWN_INTENT, "send", List.of(), List.of("send"), List.of()));

        IntentUnderstandingResult result = IntentPolicyTestSupport.engine(properties, classifier)
                .understand(request("send the payroll email", List.of(), snapshot));

        assertThat(result.actionRisk()).isEqualTo(ActionRisk.EXTERNAL_SIDE_EFFECT);
        assertThat(result.decision().outcome()).isEqualTo(IntentRouteOutcome.EXECUTION_INFO_MISSING);
        assertThat(result.decision().calibratedConfidence()).isZero();
    }

    @Test
    void shouldReturnGeneralOrOodWithoutTree() {
        IntentUnderstandingEngine engine = IntentPolicyTestSupport.engine(properties, classifier);
        IntentTreeSnapshot empty = new IntentTreeSnapshot("agent", 0, List.of(), Map.of());

        assertThat(engine.understand(request("hello", List.of(), empty)).decision().outcome())
                .isEqualTo(IntentRouteOutcome.GENERAL_CHAT);
        assertThat(engine.understand(request("football scores", List.of(), empty)).decision().outcome())
                .isEqualTo(IntentRouteOutcome.OUT_OF_DOMAIN);
    }

    private StructuredIntentClassifier.Result success(IntentRouteOutcome outcome,
                                                       String primary,
                                                       List<String> secondary,
                                                       List<String> ranked,
                                                       List<MissingDimension> missing) {
        return new StructuredIntentClassifier.Result(outcome, primary, secondary, ranked, missing,
                List.of("semantic_match"), IntentClassifierFailure.NONE);
    }

    private IntentUnderstandingRequest request(String text,
                                               List<IntentUnderstandingRequest.RecentTurn> context,
                                               IntentTreeSnapshot snapshot) {
        return new IntentUnderstandingRequest(
                "agent", "session", text, context, true, false,
                null, snapshot, null, AgentExecutionMode.REACT);
    }

    private IntentTreeSnapshot snapshot(IntentNodeDTO... nodes) {
        return new IntentTreeSnapshot("agent", 1, List.of(nodes), Map.of());
    }

    private IntentNodeDTO leaf(String id, String name) {
        return IntentNodeDTO.builder().id(id).name(name).description(name + " description")
                .examples(List.of(name + " details", name + " policy"))
                .intentKind(IntentKind.KB).enabled(true).build();
    }

    private IntentNodeDTO toolLeaf(String id, String name) {
        IntentNodeDTO node = leaf(id, name);
        node.setIntentKind(IntentKind.TOOL);
        node.setAllowedTools(List.of("mailTool"));
        return node;
    }
}
