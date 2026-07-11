package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.contract.IntentLabel;
import com.yulong.chatagent.agent.runtime.contract.SourceNeed;
import com.yulong.chatagent.intent.model.IntentKind;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Single production owner that assembles evidence and emits one typed route decision. */
@Component
public class IntentUnderstandingEngine {

    private final IntentCandidateGenerator candidateGenerator;
    private final IntentTreeQualityValidator qualityValidator;
    private final StructuredIntentClassifier structuredClassifier;
    private final IntentDecisionPolicy decisionPolicy;
    private final IntentPolicyProfileLoader profileLoader;
    private final IntentPolicyProperties properties;
    private final IntentSignalAnalyzer signalAnalyzer;
    private final IntentPolicyMetrics metrics;

    public IntentUnderstandingEngine(IntentCandidateGenerator candidateGenerator,
                                     IntentTreeQualityValidator qualityValidator,
                                     StructuredIntentClassifier structuredClassifier,
                                     IntentDecisionPolicy decisionPolicy,
                                     IntentPolicyProfileLoader profileLoader,
                                     IntentPolicyProperties properties,
                                     IntentSignalAnalyzer signalAnalyzer,
                                     IntentPolicyMetrics metrics) {
        this.candidateGenerator = candidateGenerator;
        this.qualityValidator = qualityValidator;
        this.structuredClassifier = structuredClassifier;
        this.decisionPolicy = decisionPolicy;
        this.profileLoader = profileLoader;
        this.properties = properties;
        this.signalAnalyzer = signalAnalyzer;
        this.metrics = metrics;
    }

    public IntentUnderstandingResult understand(IntentUnderstandingRequest request) {
        IntentPolicyProfile profile = profileLoader.loadConfigured();
        if (request == null || !StringUtils.hasText(request.userInput())) {
            return emptyResult(decision(IntentRouteOutcome.GENERAL_CHAT, "blank_input", profile), false, false);
        }
        IntentSignalAnalyzer.IntentTurnSignals signals = signalAnalyzer.analyze(request.userInput());
        if (request.snapshot() == null || request.snapshot().isEmpty()) {
            IntentRouteOutcome outcome = signals.outOfDomain() && !signals.generalConversation()
                    ? IntentRouteOutcome.OUT_OF_DOMAIN : IntentRouteOutcome.GENERAL_CHAT;
            return result(decision(outcome, "no_intent_tree", profile), signals,
                    SourceNeed.NONE, false, request.contextTruncated());
        }

        String evidenceText = evidenceText(request, signals);
        boolean contextUsed = signals.contextDependent()
                && !signals.explicitTopicSwitch()
                && !evidenceText.equals(request.userInput());
        List<IntentCandidate> allCandidates = candidateGenerator.generate(request.snapshot(), evidenceText);
        List<IntentCandidate> classifierCandidates = candidateGenerator.limit(
                allCandidates, profile.maxClassifierCandidates());
        IntentTreeQualityValidator.IntentTreeQualityReport quality = qualityValidator.validate(
                request.snapshot(), profile.minimumReviewedExamples());
        metrics.recordTreeQuality(quality);

        IntentDecision deterministic = decisionPolicy.deterministicDecision(
                classifierCandidates, quality, signals, profile);
        if (deterministic != null) {
            deterministic = addContextReasons(deterministic, request, contextUsed);
            metrics.recordDecision(deterministic, properties.getMode());
            IntentKind kind = request.snapshot().findNode(deterministic.primaryNodeId()).getIntentKind();
            SourceNeed deterministicSource = signalAnalyzer.deriveSourceNeed(
                    signals, kind, deterministic.outcome());
            return result(deterministic, signals, deterministicSource,
                    contextUsed, request.contextTruncated());
        }

        StructuredIntentClassifier.Result classifierResult = null;
        if (properties.getMode() != IntentPolicyMode.SAFE) {
            classifierResult = structuredClassifier.classify(request, classifierCandidates);
            if (classifierResult != null) {
                metrics.recordClassifierFailure(classifierResult.failure());
            }
        }
        IntentDecision decision = decisionPolicy.decide(
                classifierCandidates, classifierResult, quality, signals, profile);
        decision = addContextReasons(decision, request, contextUsed);
        metrics.recordDecision(decision, properties.getMode());

        IntentKind primaryKind = null;
        if (decision.hasPrimaryNode()) {
            var node = request.snapshot().findNode(decision.primaryNodeId());
            primaryKind = node == null ? null : node.getIntentKind();
        }
        SourceNeed sourceNeed = signalAnalyzer.deriveSourceNeed(
                signals, primaryKind, decision.outcome());
        return result(decision, signals, sourceNeed, contextUsed, request.contextTruncated());
    }

    private String evidenceText(IntentUnderstandingRequest request,
                                IntentSignalAnalyzer.IntentTurnSignals signals) {
        if (signals.explicitTopicSwitch()) {
            String stripped = signalAnalyzer.stripTopicSwitchPrefix(request.userInput());
            return StringUtils.hasText(stripped) ? stripped : request.userInput();
        }
        if (!signals.contextDependent() || request.recentTurns().isEmpty()) {
            return request.userInput();
        }
        for (int i = request.recentTurns().size() - 1; i >= 0; i--) {
            IntentUnderstandingRequest.RecentTurn turn = request.recentTurns().get(i);
            if ("user".equals(turn.role()) && StringUtils.hasText(turn.text())) {
                return turn.text() + "\n" + request.userInput();
            }
        }
        return request.userInput();
    }

    private IntentDecision addContextReasons(IntentDecision decision,
                                             IntentUnderstandingRequest request,
                                             boolean contextUsed) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>(decision.reasonCodes());
        if (!request.contextAvailable()) {
            reasons.add("context_unavailable");
        }
        if (request.contextTruncated()) {
            reasons.add("context_truncated");
        }
        if (contextUsed) {
            reasons.add("context_continuation");
        }
        return decision.withReasonCodes(List.copyOf(reasons));
    }

    private IntentUnderstandingResult result(IntentDecision decision,
                                             IntentSignalAnalyzer.IntentTurnSignals signals,
                                             SourceNeed sourceNeed,
                                             boolean contextUsed,
                                             boolean contextTruncated) {
        List<IntentLabel> secondary = new ArrayList<>(signals.secondaryIntents());
        if (decision.outcome() == IntentRouteOutcome.MULTI_INTENT
                && !secondary.contains(IntentLabel.MULTI_INTENT)) {
            secondary.add(IntentLabel.MULTI_INTENT);
        }
        return new IntentUnderstandingResult(
                decision, sourceNeed, signals.timeSensitivity(), signals.actionRisk(),
                List.copyOf(new LinkedHashSet<>(secondary)), contextUsed, contextTruncated);
    }

    private IntentUnderstandingResult emptyResult(IntentDecision decision,
                                                  boolean contextUsed,
                                                  boolean contextTruncated) {
        return new IntentUnderstandingResult(
                decision, SourceNeed.NONE,
                com.yulong.chatagent.agent.runtime.contract.TimeSensitivity.UNKNOWN,
                com.yulong.chatagent.agent.runtime.contract.ActionRisk.READ_ONLY,
                List.of(), contextUsed, contextTruncated);
    }

    private IntentDecision decision(IntentRouteOutcome outcome,
                                    String reason,
                                    IntentPolicyProfile profile) {
        return new IntentDecision(
                outcome, null, List.of(), List.of(), List.of(),
                IntentDecisionSource.SAFE_FALLBACK,
                profile.heuristicAcceptConfidence(),
                ConfidenceStatus.CALIBRATED,
                profile.version(),
                List.of(reason)
        );
    }
}
