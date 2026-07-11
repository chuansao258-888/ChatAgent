package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.contract.SourceReferenceClassifier;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IntentDecisionPolicyTest {

    private final IntentPolicyProperties properties = new IntentPolicyProperties();
    private final IntentDecisionPolicy policy = new IntentDecisionPolicy(properties);
    private final IntentSignalAnalyzer analyzer = new IntentSignalAnalyzer(new SourceReferenceClassifier());
    private final IntentPolicyProfile profile = IntentPolicyTestSupport.profile();

    @BeforeEach
    void setUp() {
        properties.setMode(IntentPolicyMode.ENFORCE);
    }

    @Test
    void shouldAcceptValidClassifierSelectionInsideQualityEnvelope() {
        IntentCandidate candidate = candidate("leave", 0.8d, 0.5d, false);
        IntentDecision decision = policy.decide(
                List.of(candidate),
                result(IntentRouteOutcome.KNOWN_INTENT, "leave", List.of(), List.of("leave"), List.of()),
                quality("leave"), analyzer.analyze("annual leave policy"), profile);

        assertThat(decision.outcome()).isEqualTo(IntentRouteOutcome.KNOWN_INTENT);
        assertThat(decision.primaryNodeId()).isEqualTo("leave");
        assertThat(decision.decisionSource()).isEqualTo(IntentDecisionSource.CLASSIFIER);
        assertThat(decision.confidenceStatus()).isEqualTo(ConfidenceStatus.CALIBRATED);
    }

    @Test
    void shouldClarifyClassifierSelectionForIneligibleLeaf() {
        IntentCandidate candidate = candidate("leave", 0.8d, 0.5d, false);
        IntentDecision decision = policy.decide(
                List.of(candidate),
                result(IntentRouteOutcome.KNOWN_INTENT, "leave", List.of(), List.of("leave"), List.of()),
                quality(), analyzer.analyze("annual leave policy"), profile);

        assertThat(decision.outcome()).isEqualTo(IntentRouteOutcome.AMBIGUOUS_ROUTE);
        assertThat(decision.reasonCodes()).contains("tree_quality_ineligible");
    }

    @Test
    void shouldUseFrozenThresholdImmediatelyBelowEqualAndAbove() {
        assertThat(fallback(candidate("leave", profile.acceptScore() - 0.01d,
                profile.ambiguityGap() + 0.01d, false)).outcome())
                .isEqualTo(IntentRouteOutcome.AMBIGUOUS_ROUTE);
        assertThat(fallback(candidate("leave", profile.acceptScore(),
                profile.ambiguityGap() + 0.01d, false)).outcome())
                .isEqualTo(IntentRouteOutcome.KNOWN_INTENT);
        assertThat(fallback(candidate("leave", profile.acceptScore() + 0.01d,
                profile.ambiguityGap() + 0.05d, false)).outcome())
                .isEqualTo(IntentRouteOutcome.KNOWN_INTENT);
    }

    @Test
    void shouldNotAcceptAtAmbiguityGapBoundary() {
        IntentDecision decision = fallback(candidate(
                "leave", profile.acceptScore() + 0.2d, profile.ambiguityGap(), false));

        assertThat(decision.outcome()).isEqualTo(IntentRouteOutcome.AMBIGUOUS_ROUTE);
    }

    @Test
    void shouldUseClarificationThresholdImmediatelyBelowEqualAndAbove() {
        assertThat(fallback(candidate("leave", profile.clarifyScore() - 0.01d,
                profile.clarifyScore() - 0.01d, false)).outcome())
                .isEqualTo(IntentRouteOutcome.GENERAL_CHAT);
        assertThat(fallback(candidate("leave", profile.clarifyScore(),
                profile.clarifyScore(), false)).outcome())
                .isEqualTo(IntentRouteOutcome.AMBIGUOUS_ROUTE);
        assertThat(fallback(candidate("leave", profile.clarifyScore() + 0.01d,
                profile.clarifyScore() + 0.01d, false)).outcome())
                .isEqualTo(IntentRouteOutcome.AMBIGUOUS_ROUTE);
    }

    @Test
    void shouldBlockUnconfirmedExternalActionEvenWhenClassifierSelects() {
        IntentCandidate candidate = candidate("send", 2.0d, 2.0d, true);
        IntentDecision decision = policy.decide(
                List.of(candidate),
                result(IntentRouteOutcome.KNOWN_INTENT, "send", List.of(), List.of("send"), List.of()),
                quality("send"), analyzer.analyze("send the payroll email"), profile);

        assertThat(decision.outcome()).isEqualTo(IntentRouteOutcome.EXECUTION_INFO_MISSING);
        assertThat(decision.missingDimensions()).contains(MissingDimension.CONFIRMATION);
        assertThat(decision.calibratedConfidence()).isZero();
    }

    @Test
    void shouldBlockUnconfirmedExternalActionWhenClassifierFails() {
        IntentCandidate candidate = candidate("send", 2.0d, 2.0d, true);

        IntentDecision decision = policy.decide(
                List.of(candidate), IntentPolicyTestSupport.classifierFailure(),
                quality("send"), analyzer.analyze("send the payroll email"), profile);

        assertThat(decision.outcome()).isEqualTo(IntentRouteOutcome.EXECUTION_INFO_MISSING);
        assertThat(decision.missingDimensions()).contains(MissingDimension.CONFIRMATION);
        assertThat(decision.confidenceStatus()).isEqualTo(ConfidenceStatus.UNCALIBRATED);
    }

    @Test
    void shouldAllowOnlyUniqueReviewedExactMatchInSafeMode() {
        properties.setMode(IntentPolicyMode.SAFE);
        IntentCandidate exact = candidate("leave", 2.0d, 2.0d, true);

        IntentDecision accepted = policy.decide(List.of(exact), null, quality("leave"),
                analyzer.analyze("annual leave"), profile);
        IntentDecision uncertain = policy.decide(List.of(candidate("leave", 1.4d, 0.4d, false)),
                null, quality("leave"), analyzer.analyze("leave information"), profile);

        assertThat(accepted.outcome()).isEqualTo(IntentRouteOutcome.KNOWN_INTENT);
        assertThat(uncertain.outcome()).isEqualTo(IntentRouteOutcome.AMBIGUOUS_ROUTE);
    }

    @Test
    void shouldRejectCompetingExactMatchesInSafeMode() {
        properties.setMode(IntentPolicyMode.SAFE);
        IntentCandidate first = candidate("leave", 2.0d, 0.0d, true);
        IntentCandidate second = candidate("absence", 2.0d, 2.0d, true);

        IntentDecision decision = policy.decide(
                List.of(first, second), null, quality("leave", "absence"),
                analyzer.analyze("time off"), profile);

        assertThat(decision.outcome()).isEqualTo(IntentRouteOutcome.AMBIGUOUS_ROUTE);
    }

    @Test
    void shouldPreserveCompatibleReadOnlyMultiIntent() {
        List<IntentCandidate> candidates = List.of(
                candidate("leave", 1.5d, 0.3d, false),
                candidate("expense", 1.4d, 1.4d, false));
        IntentDecision decision = policy.decide(candidates,
                result(IntentRouteOutcome.MULTI_INTENT, "leave", List.of("expense"),
                        List.of("leave", "expense"), List.of()),
                quality("leave", "expense"), analyzer.analyze("annual leave and also travel expense"), profile);

        assertThat(decision.outcome()).isEqualTo(IntentRouteOutcome.MULTI_INTENT);
        assertThat(decision.secondaryNodeIds()).containsExactly("expense");
    }

    private IntentDecision fallback(IntentCandidate candidate) {
        return policy.decide(List.of(candidate), IntentPolicyTestSupport.classifierFailure(),
                quality(candidate.node().getId()), analyzer.analyze("business request"), profile);
    }

    private IntentCandidate candidate(String id, double score, double gap, boolean exact) {
        IntentNodeDTO node = IntentNodeDTO.builder().id(id).name(id)
                .description("description").examples(List.of(id + " one", id + " two"))
                .intentKind(IntentKind.KB).enabled(true).build();
        return new IntentCandidate(node, id, score, gap, exact, false, 0, List.of("test_evidence"));
    }

    private StructuredIntentClassifier.Result result(IntentRouteOutcome outcome,
                                                     String primary,
                                                     List<String> secondary,
                                                     List<String> ranked,
                                                     List<MissingDimension> missing) {
        return new StructuredIntentClassifier.Result(
                outcome, primary, secondary, ranked, missing, List.of("semantic_match"),
                IntentClassifierFailure.NONE);
    }

    private IntentTreeQualityValidator.IntentTreeQualityReport quality(String... eligible) {
        return new IntentTreeQualityValidator.IntentTreeQualityReport(List.of(), Set.of(eligible));
    }

}
