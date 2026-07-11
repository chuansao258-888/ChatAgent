package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.contract.ActionRisk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Applies the frozen profile and risk/quality overrides to typed classifier evidence. */
@Component
public class IntentDecisionPolicy {

    private final IntentPolicyProperties properties;

    public IntentDecisionPolicy(IntentPolicyProperties properties) {
        this.properties = properties;
    }

    public IntentDecision decide(List<IntentCandidate> candidates,
                                 StructuredIntentClassifier.Result classifierResult,
                                 IntentTreeQualityValidator.IntentTreeQualityReport qualityReport,
                                 IntentSignalAnalyzer.IntentTurnSignals signals,
                                 IntentPolicyProfile profile) {
        List<IntentCandidate> ranked = candidates == null ? List.of() : List.copyOf(candidates);
        IntentDecision deterministic = deterministicDecision(ranked, qualityReport, signals, profile);
        if (deterministic != null) {
            return deterministic;
        }

        if (properties.getMode() == IntentPolicyMode.SAFE) {
            return safeFallback(ranked, qualityReport, signals, profile, "safe_mode_non_exact");
        }

        if (classifierResult != null && classifierResult.successful()) {
            IntentDecision classified = fromClassifier(
                    ranked, classifierResult, qualityReport, signals, profile);
            if (classified != null) {
                return classified;
            }
        }

        String failureReason = classifierResult == null
                ? "classifier_not_run"
                : "classifier_" + classifierResult.failure().name().toLowerCase(java.util.Locale.ROOT);
        return calibratedFallback(ranked, qualityReport, signals, profile, failureReason);
    }

    public IntentDecision deterministicDecision(List<IntentCandidate> candidates,
                                                 IntentTreeQualityValidator.IntentTreeQualityReport qualityReport,
                                                 IntentSignalAnalyzer.IntentTurnSignals signals,
                                                 IntentPolicyProfile profile) {
        List<IntentCandidate> ranked = candidates == null ? List.of() : List.copyOf(candidates);
        IntentCandidate exact = uniqueSafeExact(ranked, qualityReport, signals);
        if (exact == null) {
            return null;
        }
        return known(exact, ranked, List.of(), IntentDecisionSource.DETERMINISTIC,
                profile.deterministicExactConfidence(), profile, List.of("unique_reviewed_match"));
    }

    private IntentDecision fromClassifier(List<IntentCandidate> candidates,
                                          StructuredIntentClassifier.Result result,
                                          IntentTreeQualityValidator.IntentTreeQualityReport quality,
                                          IntentSignalAnalyzer.IntentTurnSignals signals,
                                          IntentPolicyProfile profile) {
        List<IntentCandidate> modelRanked = modelRanked(candidates, result.rankedCandidateIds());
        List<String> reasons = mergeReasons(result.reasonCodes(), List.of("classifier_schema_valid"));
        return switch (result.outcome()) {
            case GENERAL_CHAT -> passthrough(IntentRouteOutcome.GENERAL_CHAT, modelRanked,
                    IntentDecisionSource.CLASSIFIER, profile, reasons);
            case OUT_OF_DOMAIN -> passthrough(IntentRouteOutcome.OUT_OF_DOMAIN, modelRanked,
                    IntentDecisionSource.CLASSIFIER, profile, reasons);
            case AMBIGUOUS_ROUTE -> ambiguous(modelRanked, quality, profile, reasons);
            case EXECUTION_INFO_MISSING -> missingInfo(result.primaryCandidateId(), modelRanked,
                    result.missingDimensions(), profile, reasons);
            case KNOWN_INTENT -> classifiedKnown(result.primaryCandidateId(), modelRanked,
                    quality, signals, profile, reasons);
            case MULTI_INTENT -> classifiedMulti(result, modelRanked, quality, signals, profile, reasons);
        };
    }

    private IntentDecision classifiedKnown(String primaryId,
                                           List<IntentCandidate> candidates,
                                           IntentTreeQualityValidator.IntentTreeQualityReport quality,
                                           IntentSignalAnalyzer.IntentTurnSignals signals,
                                           IntentPolicyProfile profile,
                                           List<String> reasons) {
        IntentCandidate primary = find(candidates, primaryId);
        if (primary == null) {
            return null;
        }
        if (signals.actionRisk() != ActionRisk.READ_ONLY || signals.sourceConflict()) {
            return missingInfo(primaryId, candidates, missingForSignals(signals), profile,
                    mergeReasons(reasons, List.of("risk_or_source_override")));
        }
        if (!quality.isAutoRouteEligible(primaryId)) {
            return ambiguous(candidates, quality, profile,
                    mergeReasons(reasons, List.of("tree_quality_ineligible")));
        }
        return known(primary, candidates, List.of(), IntentDecisionSource.CLASSIFIER,
                profile.classifierAgreementConfidence(), profile, reasons);
    }

    private IntentDecision classifiedMulti(StructuredIntentClassifier.Result result,
                                           List<IntentCandidate> candidates,
                                           IntentTreeQualityValidator.IntentTreeQualityReport quality,
                                           IntentSignalAnalyzer.IntentTurnSignals signals,
                                           IntentPolicyProfile profile,
                                           List<String> reasons) {
        List<String> ids = new ArrayList<>();
        ids.add(result.primaryCandidateId());
        ids.addAll(result.secondaryCandidateIds());
        boolean eligible = ids.stream().allMatch(quality::isAutoRouteEligible);
        if (!eligible || signals.actionRisk() != ActionRisk.READ_ONLY || signals.sourceConflict()) {
            List<MissingDimension> missing = missingForSignals(signals);
            if (missing.isEmpty()) {
                missing = List.of(MissingDimension.ORDER);
            }
            return missingInfo(result.primaryCandidateId(), candidates, missing, profile,
                    mergeReasons(reasons, List.of("multi_intent_requires_clarification")));
        }
        return decision(
                IntentRouteOutcome.MULTI_INTENT,
                result.primaryCandidateId(),
                result.secondaryCandidateIds(),
                candidates,
                List.of(),
                IntentDecisionSource.CLASSIFIER,
                profile.classifierAgreementConfidence(),
                profile,
                reasons
        );
    }

    private IntentDecision calibratedFallback(List<IntentCandidate> candidates,
                                              IntentTreeQualityValidator.IntentTreeQualityReport quality,
                                              IntentSignalAnalyzer.IntentTurnSignals signals,
                                              IntentPolicyProfile profile,
                                              String reason) {
        if (signals.actionRisk() != ActionRisk.READ_ONLY || signals.sourceConflict()) {
            return missingInfo(null, candidates, missingForSignals(signals), profile, List.of(reason));
        }
        if (candidates.isEmpty() || candidates.get(0).lexicalScore() < profile.clarifyScore()) {
            return generalOrOod(candidates, signals, profile, reason);
        }
        IntentCandidate top = candidates.get(0);
        if (top.lexicalScore() >= profile.acceptScore()
                && top.lexicalGap() > profile.ambiguityGap()
                && quality.isAutoRouteEligible(top.node().getId())) {
            return known(top, candidates, List.of(), IntentDecisionSource.CALIBRATED_POLICY,
                    profile.heuristicAcceptConfidence(), profile, List.of(reason, "profile_accept_band"));
        }
        return ambiguous(candidates, quality, profile, List.of(reason, "profile_clarify_band"));
    }

    private IntentDecision safeFallback(List<IntentCandidate> candidates,
                                        IntentTreeQualityValidator.IntentTreeQualityReport quality,
                                        IntentSignalAnalyzer.IntentTurnSignals signals,
                                        IntentPolicyProfile profile,
                                        String reason) {
        if (signals.actionRisk() != ActionRisk.READ_ONLY || signals.sourceConflict()) {
            return missingInfo(null, candidates, missingForSignals(signals), profile, List.of(reason));
        }
        if (!candidates.isEmpty() && candidates.get(0).lexicalScore() >= profile.clarifyScore()) {
            return ambiguous(candidates, quality, profile, List.of(reason));
        }
        return generalOrOod(candidates, signals, profile, reason);
    }

    private IntentCandidate uniqueSafeExact(List<IntentCandidate> candidates,
                                            IntentTreeQualityValidator.IntentTreeQualityReport quality,
                                            IntentSignalAnalyzer.IntentTurnSignals signals) {
        if (signals.multiIntentSignal() || signals.sourceConflict()
                || signals.actionRisk() != ActionRisk.READ_ONLY || signals.explicitTopicSwitch()) {
            return null;
        }
        List<IntentCandidate> exact = candidates.stream()
                .filter(candidate -> candidate.exactNameMatch() || candidate.exactExampleMatch())
                .filter(candidate -> quality.isAutoRouteEligible(candidate.node().getId()))
                .toList();
        return exact.size() == 1 ? exact.get(0) : null;
    }

    private IntentDecision known(IntentCandidate primary,
                                 List<IntentCandidate> candidates,
                                 List<String> secondary,
                                 IntentDecisionSource source,
                                 double confidence,
                                 IntentPolicyProfile profile,
                                 List<String> reasons) {
        return decision(IntentRouteOutcome.KNOWN_INTENT, primary.node().getId(), secondary,
                candidates, List.of(), source, confidence, profile, reasons);
    }

    private IntentDecision ambiguous(List<IntentCandidate> candidates,
                                     IntentTreeQualityValidator.IntentTreeQualityReport quality,
                                     IntentPolicyProfile profile,
                                     List<String> reasons) {
        List<IntentCandidate> eligibleFirst = new ArrayList<>(candidates);
        eligibleFirst.sort((left, right) -> Boolean.compare(
                quality.isAutoRouteEligible(right.node().getId()),
                quality.isAutoRouteEligible(left.node().getId())));
        List<IntentCandidate> limited = eligibleFirst.subList(
                0, Math.min(eligibleFirst.size(), profile.maxClarificationCandidates()));
        return decision(IntentRouteOutcome.AMBIGUOUS_ROUTE, null, List.of(), limited, List.of(),
                IntentDecisionSource.CALIBRATED_POLICY, 0.0d, profile, reasons);
    }

    private IntentDecision missingInfo(String primary,
                                       List<IntentCandidate> candidates,
                                       List<MissingDimension> missing,
                                       IntentPolicyProfile profile,
                                       List<String> reasons) {
        List<MissingDimension> dimensions = missing == null || missing.isEmpty()
                ? List.of(MissingDimension.ACTION) : List.copyOf(new LinkedHashSet<>(missing));
        return decision(IntentRouteOutcome.EXECUTION_INFO_MISSING, primary, List.of(), candidates,
                dimensions, IntentDecisionSource.SAFE_FALLBACK, 0.0d, profile, reasons);
    }

    private IntentDecision generalOrOod(List<IntentCandidate> candidates,
                                        IntentSignalAnalyzer.IntentTurnSignals signals,
                                        IntentPolicyProfile profile,
                                        String reason) {
        IntentRouteOutcome outcome = signals.outOfDomain() && !signals.generalConversation()
                ? IntentRouteOutcome.OUT_OF_DOMAIN : IntentRouteOutcome.GENERAL_CHAT;
        return passthrough(outcome, candidates, IntentDecisionSource.CALIBRATED_POLICY,
                profile, List.of(reason));
    }

    private IntentDecision passthrough(IntentRouteOutcome outcome,
                                       List<IntentCandidate> candidates,
                                       IntentDecisionSource source,
                                       IntentPolicyProfile profile,
                                       List<String> reasons) {
        return decision(outcome, null, List.of(), candidates, List.of(), source,
                profile.heuristicAcceptConfidence(), profile, reasons);
    }

    private IntentDecision decision(IntentRouteOutcome outcome,
                                    String primary,
                                    List<String> secondary,
                                    List<IntentCandidate> candidates,
                                    List<MissingDimension> missing,
                                    IntentDecisionSource source,
                                    double confidence,
                                    IntentPolicyProfile profile,
                                    List<String> reasons) {
        return new IntentDecision(
                outcome,
                primary,
                secondary,
                evidence(candidates),
                missing,
                source,
                confidence,
                confidence > 0.0d ? ConfidenceStatus.CALIBRATED : ConfidenceStatus.UNCALIBRATED,
                profile.version(),
                reasons
        );
    }

    private List<IntentCandidateEvidence> evidence(List<IntentCandidate> candidates) {
        List<IntentCandidateEvidence> result = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            result.add(candidates.get(i).toEvidence(i + 1));
        }
        return List.copyOf(result);
    }

    private List<IntentCandidate> modelRanked(List<IntentCandidate> candidates, List<String> rankedIds) {
        if (rankedIds == null || rankedIds.isEmpty()) {
            return candidates;
        }
        Map<String, IntentCandidate> byId = new LinkedHashMap<>();
        candidates.forEach(candidate -> byId.put(candidate.node().getId(), candidate));
        List<IntentCandidate> result = new ArrayList<>();
        rankedIds.forEach(id -> {
            IntentCandidate candidate = byId.remove(id);
            if (candidate != null) {
                result.add(candidate);
            }
        });
        result.addAll(byId.values());
        return List.copyOf(result);
    }

    private IntentCandidate find(List<IntentCandidate> candidates, String nodeId) {
        if (nodeId == null) {
            return null;
        }
        return candidates.stream().filter(candidate -> nodeId.equals(candidate.node().getId()))
                .findFirst().orElse(null);
    }

    private List<MissingDimension> missingForSignals(IntentSignalAnalyzer.IntentTurnSignals signals) {
        List<MissingDimension> result = new ArrayList<>(signals.missingDimensions());
        if (signals.actionRisk() != ActionRisk.READ_ONLY && !result.contains(MissingDimension.CONFIRMATION)) {
            result.add(MissingDimension.CONFIRMATION);
        }
        if (signals.sourceConflict() && !result.contains(MissingDimension.SOURCE)) {
            result.add(MissingDimension.SOURCE);
        }
        return List.copyOf(result);
    }

    private List<String> mergeReasons(List<String> first, List<String> second) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (first != null) {
            result.addAll(first);
        }
        if (second != null) {
            result.addAll(second);
        }
        return List.copyOf(result);
    }
}
