package com.yulong.chatagent.intent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.contract.ActionRisk;
import com.yulong.chatagent.agent.runtime.contract.ClarificationKind;
import com.yulong.chatagent.agent.runtime.contract.SourceNeed;
import com.yulong.chatagent.agent.runtime.contract.SourceReferenceClassifier;
import com.yulong.chatagent.support.dto.IntentNodeDTO;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class IntentPolicyEvaluationSupport {

    static final String RESOURCE_ROOT = "/intent-eval/v1/";
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private IntentPolicyEvaluationSupport() {
    }

    static List<IntentEvalCase> loadCases(String split) {
        String resource = RESOURCE_ROOT + split + ".jsonl";
        try (InputStream input = requiredResource(resource);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            List<IntentEvalCase> cases = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    cases.add(MAPPER.readValue(line, IntentEvalCase.class));
                }
            }
            return List.copyOf(cases);
        } catch (IOException exception) {
            throw new IllegalStateException("Intent evaluation corpus is unreadable: " + split, exception);
        }
    }

    static IntentTreeSnapshot loadSnapshot() {
        try (InputStream input = requiredResource(RESOURCE_ROOT + "tree-fixture-enterprise-v1.json")) {
            TreeFixture fixture = MAPPER.readValue(input, TreeFixture.class);
            return new IntentTreeSnapshot(
                    "intent-eval", fixture.schemaVersion(), fixture.nodes(),
                    fixture.knowledgeBaseIdsByNodeId());
        } catch (IOException exception) {
            throw new IllegalStateException("Intent evaluation tree fixture is unreadable", exception);
        }
    }

    static CorpusManifest loadManifest() {
        try (InputStream input = requiredResource(RESOURCE_ROOT + "manifest.json")) {
            return MAPPER.readValue(input, CorpusManifest.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Intent evaluation manifest is unreadable", exception);
        }
    }

    static EvaluationReport evaluate(List<IntentEvalCase> cases,
                                     IntentTreeSnapshot snapshot,
                                     IntentPolicyProfile profile) {
        return evaluate(cases, snapshot, profile, new FixtureClassifier(cases));
    }

    static EvaluationReport evaluate(List<IntentEvalCase> cases,
                                     IntentTreeSnapshot snapshot,
                                     IntentPolicyProfile profile,
                                     StructuredIntentClassifier classifier) {
        IntentPolicyProperties properties = new IntentPolicyProperties();
        properties.setMode(IntentPolicyMode.ENFORCE);
        properties.setProfileVersion(profile.version());
        IntentPolicyProfileLoader loader = mock(IntentPolicyProfileLoader.class);
        when(loader.loadConfigured()).thenReturn(profile);
        IntentSignalAnalyzer signalAnalyzer = new IntentSignalAnalyzer(new SourceReferenceClassifier());
        IntentUnderstandingEngine engine = new IntentUnderstandingEngine(
                new IntentCandidateGenerator(), new IntentTreeQualityValidator(), classifier,
                new IntentDecisionPolicy(properties), loader, properties, signalAnalyzer,
                mock(IntentPolicyMetrics.class));
        ClarificationResolver clarificationResolver = new ClarificationResolver(signalAnalyzer);
        List<Prediction> predictions = new ArrayList<>();
        for (IntentEvalCase evalCase : cases) {
            IntentUnderstandingResult actual = engine.understand(new IntentUnderstandingRequest(
                    "intent-eval", "eval-session", evalCase.currentUserText(),
                    evalCase.recentVisibleTurns(), true, false, null, snapshot,
                    String.join(",", evalCase.sessionAssetKinds()), AgentExecutionMode.REACT));
            ClarificationResult clarification = evaluateClarification(
                    evalCase, snapshot, clarificationResolver);
            predictions.add(new Prediction(evalCase, actual, clarification));
        }
        IntentTreeQualityValidator.IntentTreeQualityReport quality =
                new IntentTreeQualityValidator().validate(snapshot, profile.minimumReviewedExamples());
        return report(predictions, quality);
    }

    static String corpusHash() {
        byte[] calibration = resourceBytes(RESOURCE_ROOT + "calibration.jsonl");
        byte[] holdout = resourceBytes(RESOURCE_ROOT + "holdout.jsonl");
        MessageDigest digest = sha256Digest();
        digest.update(calibration);
        digest.update((byte) 0);
        digest.update(holdout);
        return HexFormat.of().formatHex(digest.digest());
    }

    static String treeFixtureHash() {
        return sha256(resourceBytes(RESOURCE_ROOT + "tree-fixture-enterprise-v1.json"));
    }

    static String textFingerprint(IntentEvalCase evalCase) {
        StringBuilder normalized = new StringBuilder();
        for (IntentUnderstandingRequest.RecentTurn turn : evalCase.recentVisibleTurns()) {
            normalized.append(normalize(turn.role())).append(':')
                    .append(normalize(turn.text())).append('\n');
        }
        normalized.append(normalize(evalCase.currentUserText()));
        return sha256(normalized.toString().getBytes(StandardCharsets.UTF_8));
    }

    static IntentPolicyProfile withPolicyValues(IntentPolicyProfile base,
                                                 double acceptScore,
                                                 double clarifyScore,
                                                 double ambiguityGap,
                                                 int classifierCandidates,
                                                 int clarificationCandidates,
                                                 int minimumExamples) {
        return new IntentPolicyProfile(
                base.version(), base.corpusManifestHash(), base.classifierModelId(),
                base.promptVersion(), base.featureVersion(), base.classifierTemperature(),
                base.classifierMaxTokens(), base.treeFixtureManifestHash(), acceptScore,
                clarifyScore, ambiguityGap, classifierCandidates, clarificationCandidates,
                minimumExamples, base.deterministicExactConfidence(),
                base.classifierAgreementConfidence(), base.heuristicAcceptConfidence());
    }

    private static ClarificationResult evaluateClarification(IntentEvalCase evalCase,
                                                              IntentTreeSnapshot snapshot,
                                                              ClarificationResolver resolver) {
        if (evalCase.expectedClarificationReply() == null || evalCase.clarificationReply() == null) {
            return ClarificationResult.notEvaluated();
        }
        List<IntentNodeDTO> candidates = evalCase.acceptableCandidateIds().stream()
                .map(snapshot::findNode).filter(java.util.Objects::nonNull).toList();
        ClarificationResolver.ClarificationReply actual =
                resolver.resolveTyped(evalCase.clarificationReply(), candidates);
        List<String> selectedIds = actual.selected().stream().map(IntentNodeDTO::getId).toList();
        boolean correct = actual.outcome() == evalCase.expectedClarificationReply()
                && selectedIds.equals(evalCase.expectedClarificationNodeIds());
        return new ClarificationResult(true, correct, actual.outcome());
    }

    private static EvaluationReport report(List<Prediction> predictions,
                                           IntentTreeQualityValidator.IntentTreeQualityReport quality) {
        int total = predictions.size();
        int routeCorrect = count(predictions, Prediction::routeCorrect);
        int sourceCorrect = count(predictions, prediction ->
                prediction.actual().sourceNeed() == prediction.evalCase().expectedSourceNeed());
        int actionCorrect = count(predictions, prediction ->
                prediction.actual().actionRisk() == prediction.evalCase().expectedActionRisk());
        int negative = count(predictions, prediction -> prediction.evalCase().isNegative());
        int falseBusinessRoutes = count(predictions, prediction -> prediction.evalCase().isNegative()
                && isBusinessRoute(prediction.actual().decision().outcome()));
        int ambiguousExpected = count(predictions, prediction ->
                prediction.evalCase().expectedOutcome() == IntentRouteOutcome.AMBIGUOUS_ROUTE);
        int ambiguousTrue = count(predictions, prediction ->
                prediction.evalCase().expectedOutcome() == IntentRouteOutcome.AMBIGUOUS_ROUTE
                        && prediction.actual().decision().outcome() == IntentRouteOutcome.AMBIGUOUS_ROUTE);
        int ambiguousPredicted = count(predictions, prediction ->
                prediction.actual().decision().outcome() == IntentRouteOutcome.AMBIGUOUS_ROUTE);
        int clearTurns = count(predictions, prediction -> prediction.evalCase().isClearTurn());
        int unnecessaryClarification = count(predictions, prediction -> prediction.evalCase().isClearTurn()
                && prediction.actual().decision().outcome() == IntentRouteOutcome.AMBIGUOUS_ROUTE);
        int multiExpected = count(predictions, prediction ->
                prediction.evalCase().expectedOutcome() == IntentRouteOutcome.MULTI_INTENT);
        int multiPredicted = count(predictions, prediction ->
                prediction.actual().decision().outcome() == IntentRouteOutcome.MULTI_INTENT);
        int multiTrue = count(predictions, prediction ->
                prediction.evalCase().expectedOutcome() == IntentRouteOutcome.MULTI_INTENT
                        && prediction.actual().decision().outcome() == IntentRouteOutcome.MULTI_INTENT);
        int highRiskWrongExecution = count(predictions, prediction ->
                prediction.evalCase().expectedActionRisk() != ActionRisk.READ_ONLY
                        && (prediction.actual().decision().outcome() == IntentRouteOutcome.KNOWN_INTENT
                        || prediction.actual().decision().outcome() == IntentRouteOutcome.MULTI_INTENT));
        int covered = count(predictions, prediction -> switch (prediction.actual().decision().outcome()) {
            case KNOWN_INTENT, MULTI_INTENT, GENERAL_CHAT, OUT_OF_DOMAIN -> true;
            case AMBIGUOUS_ROUTE, EXECUTION_INFO_MISSING -> false;
        });

        List<Prediction> recovery = predictions.stream()
                .filter(prediction -> prediction.clarification().evaluated())
                .filter(prediction -> prediction.evalCase().expectedClarificationReply()
                        == ClarificationResolver.ReplyOutcome.SELECT_ONE
                        || prediction.evalCase().expectedClarificationReply()
                        == ClarificationResolver.ReplyOutcome.SELECT_MANY)
                .toList();
        List<Prediction> topicSwitch = predictions.stream()
                .filter(prediction -> prediction.clarification().evaluated())
                .filter(prediction -> prediction.evalCase().expectedClarificationReply()
                        == ClarificationResolver.ReplyOutcome.NEW_TOPIC)
                .toList();

        LeafMetrics leaf = leafMetrics(predictions);
        List<String> failed = predictions.stream().filter(prediction -> !prediction.routeCorrect())
                .map(prediction -> prediction.evalCase().caseId()).sorted().toList();
        Map<String, String> failedOutcomes = predictions.stream()
                .filter(prediction -> !prediction.routeCorrect())
                .collect(Collectors.toMap(
                        prediction -> prediction.evalCase().caseId(),
                        prediction -> prediction.evalCase().expectedOutcome().name() + "->"
                                + prediction.actual().decision().outcome().name(),
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, List<String>> failedReasons = predictions.stream()
                .filter(prediction -> !prediction.routeCorrect())
                .collect(Collectors.toMap(
                        prediction -> prediction.evalCase().caseId(),
                        prediction -> prediction.actual().decision().reasonCodes(),
                        (left, right) -> left, LinkedHashMap::new));
        List<String> sourceMismatchCaseIds = predictions.stream()
                .filter(prediction -> prediction.actual().sourceNeed()
                        != prediction.evalCase().expectedSourceNeed())
                .map(prediction -> prediction.evalCase().caseId()).sorted().toList();
        Map<String, String> outcomeByCaseId = predictions.stream()
                .collect(Collectors.toMap(
                        prediction -> prediction.evalCase().caseId(),
                        prediction -> prediction.actual().decision().outcome().name(),
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, Map<String, Integer>> confusion = confusionMatrix(predictions);
        return new EvaluationReport(
                total,
                ratio(routeCorrect, total),
                leaf.macroF1(),
                leaf.recallByLeaf(),
                leaf.supportByLeaf(),
                ratio(falseBusinessRoutes, negative),
                ratio(ambiguousTrue, ambiguousExpected),
                ratio(unnecessaryClarification, clearTurns),
                ratio(ambiguousTrue, ambiguousPredicted),
                ratio(ambiguousTrue, ambiguousExpected),
                ratio(count(recovery, prediction -> prediction.clarification().correct()), recovery.size()),
                ratio(count(topicSwitch, prediction -> prediction.clarification().correct()), topicSwitch.size()),
                f1(multiTrue, multiPredicted, multiExpected),
                ratio(sourceCorrect, total),
                ratio(actionCorrect, total),
                highRiskWrongExecution,
                ratio(covered, total),
                ratio(total - covered, total),
                sliceAccuracy(predictions, prediction -> prediction.evalCase().language()),
                sliceAccuracy(predictions, prediction -> Integer.toString(prediction.evalCase().treeDepth())),
                sliceAccuracy(predictions, prediction -> prediction.evalCase().expectedSourceNeed().name()),
                sliceAccuracy(predictions, prediction -> prediction.evalCase().expectedActionRisk().name()),
                sliceAccuracy(predictions, prediction -> prediction.evalCase().expectedOutcome().name()),
                confusion,
                failed,
                failedOutcomes,
                failedReasons,
                sourceMismatchCaseIds,
                outcomeByCaseId,
                quality.findings().stream().map(IntentTreeQualityValidator.QualityFinding::code).toList()
        );
    }

    private static LeafMetrics leafMetrics(List<Prediction> predictions) {
        Set<String> labels = predictions.stream()
                .filter(prediction -> prediction.evalCase().expectedOutcome() == IntentRouteOutcome.KNOWN_INTENT)
                .flatMap(prediction -> prediction.evalCase().allowedPrimaryNodeIds().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Double> recall = new LinkedHashMap<>();
        Map<String, Integer> support = new LinkedHashMap<>();
        List<Double> f1Values = new ArrayList<>();
        for (String label : labels) {
            int tp = count(predictions, prediction -> expectedLeaf(prediction, label)
                    && predictedLeaf(prediction, label));
            int fp = count(predictions, prediction -> !expectedLeaf(prediction, label)
                    && predictedLeaf(prediction, label));
            int fn = count(predictions, prediction -> expectedLeaf(prediction, label)
                    && !predictedLeaf(prediction, label));
            int labelSupport = tp + fn;
            support.put(label, labelSupport);
            recall.put(label, ratio(tp, labelSupport));
            f1Values.add(f1(tp, tp + fp, tp + fn));
        }
        double macro = f1Values.stream().mapToDouble(Double::doubleValue).average().orElse(1.0d);
        return new LeafMetrics(macro, Map.copyOf(recall), Map.copyOf(support));
    }

    private static boolean expectedLeaf(Prediction prediction, String label) {
        return prediction.evalCase().expectedOutcome() == IntentRouteOutcome.KNOWN_INTENT
                && prediction.evalCase().allowedPrimaryNodeIds().contains(label);
    }

    private static boolean predictedLeaf(Prediction prediction, String label) {
        return prediction.actual().decision().outcome() == IntentRouteOutcome.KNOWN_INTENT
                && label.equals(prediction.actual().decision().primaryNodeId());
    }

    private static Map<String, Map<String, Integer>> confusionMatrix(List<Prediction> predictions) {
        Map<String, Map<String, Integer>> matrix = new LinkedHashMap<>();
        for (Prediction prediction : predictions) {
            String expected = prediction.evalCase().expectedOutcome().name();
            String actual = prediction.actual().decision().outcome().name();
            matrix.computeIfAbsent(expected, ignored -> new LinkedHashMap<>())
                    .merge(actual, 1, Integer::sum);
        }
        return matrix;
    }

    private static Map<String, Double> sliceAccuracy(
            List<Prediction> predictions, Function<Prediction, String> classifier) {
        Map<String, List<Prediction>> groups = predictions.stream()
                .collect(Collectors.groupingBy(classifier, LinkedHashMap::new, Collectors.toList()));
        Map<String, Double> result = new LinkedHashMap<>();
        groups.forEach((key, values) -> result.put(
                key, ratio(count(values, Prediction::routeCorrect), values.size())));
        return Map.copyOf(result);
    }

    private static boolean isBusinessRoute(IntentRouteOutcome outcome) {
        return outcome == IntentRouteOutcome.KNOWN_INTENT
                || outcome == IntentRouteOutcome.MULTI_INTENT
                || outcome == IntentRouteOutcome.EXECUTION_INFO_MISSING;
    }

    private static int count(List<Prediction> values, java.util.function.Predicate<Prediction> predicate) {
        return (int) values.stream().filter(predicate).count();
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 1.0d : (double) numerator / denominator;
    }

    private static double f1(int truePositive, int predictedPositive, int expectedPositive) {
        double precision = ratio(truePositive, predictedPositive);
        double recall = ratio(truePositive, expectedPositive);
        return precision + recall == 0.0d ? 0.0d : 2.0d * precision * recall / (precision + recall);
    }

    private static InputStream requiredResource(String path) {
        InputStream input = IntentPolicyEvaluationSupport.class.getResourceAsStream(path);
        if (input == null) {
            throw new IllegalStateException("Missing intent evaluation resource: " + path);
        }
        return input;
    }

    private static byte[] resourceBytes(String path) {
        try (InputStream input = requiredResource(path)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot hash intent evaluation resource: " + path, exception);
        }
    }

    private static String sha256(byte[] value) {
        return HexFormat.of().formatHex(sha256Digest().digest(value));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    record IntentEvalCase(
            String caseId,
            String seedId,
            String split,
            String language,
            String intentTreeFixtureId,
            List<IntentUnderstandingRequest.RecentTurn> recentVisibleTurns,
            String currentUserText,
            List<String> sessionAssetKinds,
            IntentRouteOutcome expectedOutcome,
            List<String> allowedPrimaryNodeIds,
            List<String> expectedSecondaryNodeIds,
            List<String> acceptableCandidateIds,
            ClarificationKind expectedClarificationKind,
            SourceNeed expectedSourceNeed,
            ActionRisk expectedActionRisk,
            String clarificationReply,
            ClarificationResolver.ReplyOutcome expectedClarificationReply,
            List<String> expectedClarificationNodeIds,
            IntentClassifierFailure fixtureFailure,
            int treeDepth,
            List<String> tags,
            String reviewStatus
    ) {
        IntentEvalCase {
            recentVisibleTurns = copy(recentVisibleTurns);
            sessionAssetKinds = copy(sessionAssetKinds);
            allowedPrimaryNodeIds = copy(allowedPrimaryNodeIds);
            expectedSecondaryNodeIds = copy(expectedSecondaryNodeIds);
            acceptableCandidateIds = copy(acceptableCandidateIds);
            expectedClarificationNodeIds = copy(expectedClarificationNodeIds);
            tags = copy(tags);
            fixtureFailure = fixtureFailure == null ? IntentClassifierFailure.NONE : fixtureFailure;
        }

        boolean isNegative() {
            return expectedOutcome == IntentRouteOutcome.GENERAL_CHAT
                    || expectedOutcome == IntentRouteOutcome.OUT_OF_DOMAIN;
        }

        boolean isClearTurn() {
            return expectedOutcome == IntentRouteOutcome.KNOWN_INTENT || isNegative();
        }

        private static <T> List<T> copy(List<T> value) {
            return value == null ? List.of() : List.copyOf(value);
        }
    }

    record TreeFixture(
            String fixtureId,
            int schemaVersion,
            List<IntentNodeDTO> nodes,
            Map<String, List<String>> knowledgeBaseIdsByNodeId
    ) {
    }

    record CorpusManifest(
            String datasetId,
            String version,
            String reviewStatus,
            String reviewerRole,
            String calibrationResource,
            String holdoutResource,
            String treeFixtureResource,
            int calibrationCases,
            int holdoutCases,
            List<String> supportedLanguages,
            String fingerprintAlgorithm,
            String corpusManifestHash,
            String treeFixtureManifestHash,
            String classifierModelId,
            String promptVersion,
            String featureVersion,
            double classifierTemperature,
            int classifierMaxTokens
    ) {
    }

    record EvaluationReport(
            int totalCases,
            double routeAccuracy,
            double knownLeafMacroF1,
            Map<String, Double> recallByLeaf,
            Map<String, Integer> supportByLeaf,
            double falseBusinessRouteRate,
            double ambiguityRecall,
            double unnecessaryClarificationRate,
            double clarificationPrecision,
            double clarificationRecall,
            double clarificationRecoveryAccuracy,
            double newTopicReleaseAccuracy,
            double multiIntentF1,
            double sourceNeedAccuracy,
            double actionRiskAccuracy,
            int highRiskWrongAutomaticExecutionCount,
            double coverage,
            double abstentionRate,
            Map<String, Double> accuracyByLanguage,
            Map<String, Double> accuracyByDepth,
            Map<String, Double> accuracyBySource,
            Map<String, Double> accuracyByRisk,
            Map<String, Double> accuracyByOutcome,
            Map<String, Map<String, Integer>> confusionMatrix,
            List<String> failedCaseIds,
            Map<String, String> failedCaseOutcomes,
            Map<String, List<String>> failedCaseReasonCodes,
            List<String> sourceMismatchCaseIds,
            Map<String, String> outcomeByCaseId,
            List<String> treeQualityFindingCodes
    ) {
        boolean passesReleaseGates() {
            boolean leafRecall = recallByLeaf.entrySet().stream()
                    .filter(entry -> supportByLeaf.getOrDefault(entry.getKey(), 0) >= 10)
                    .allMatch(entry -> entry.getValue() >= 0.80d);
            return knownLeafMacroF1 >= 0.90d
                    && leafRecall
                    && falseBusinessRouteRate <= 0.02d
                    && ambiguityRecall >= 0.90d
                    && unnecessaryClarificationRate <= 0.08d
                    && clarificationRecoveryAccuracy >= 0.90d
                    && newTopicReleaseAccuracy >= 0.95d
                    && multiIntentF1 >= 0.85d
                    && sourceNeedAccuracy >= 0.92d
                    && highRiskWrongAutomaticExecutionCount == 0;
        }
    }

    private record Prediction(
            IntentEvalCase evalCase,
            IntentUnderstandingResult actual,
            ClarificationResult clarification
    ) {
        boolean routeCorrect() {
            IntentDecision decision = actual.decision();
            if (decision.outcome() != evalCase.expectedOutcome()) {
                return false;
            }
            if (!evalCase.allowedPrimaryNodeIds().isEmpty()) {
                String primaryId = decision.primaryNodeId();
                if (primaryId == null || !evalCase.allowedPrimaryNodeIds().contains(primaryId)) {
                    return false;
                }
            }
            if (!new LinkedHashSet<>(decision.secondaryNodeIds())
                    .containsAll(evalCase.expectedSecondaryNodeIds())) {
                return false;
            }
            if (evalCase.expectedOutcome() == IntentRouteOutcome.AMBIGUOUS_ROUTE) {
                Set<String> actualIds = decision.rankedCandidates().stream()
                        .map(IntentCandidateEvidence::nodeId).collect(Collectors.toSet());
                return actualIds.containsAll(evalCase.acceptableCandidateIds());
            }
            return true;
        }
    }

    private record ClarificationResult(
            boolean evaluated,
            boolean correct,
            ClarificationResolver.ReplyOutcome actualOutcome
    ) {
        static ClarificationResult notEvaluated() {
            return new ClarificationResult(false, false, null);
        }
    }

    private record LeafMetrics(
            double macroF1,
            Map<String, Double> recallByLeaf,
            Map<String, Integer> supportByLeaf
    ) {
    }

    private static final class FixtureClassifier extends StructuredIntentClassifier {
        private final Map<String, IntentEvalCase> byText;

        private FixtureClassifier(List<IntentEvalCase> cases) {
            super(null, null, MAPPER, "fixture");
            this.byText = cases.stream().collect(Collectors.toMap(
                    IntentEvalCase::currentUserText, Function.identity()));
        }

        @Override
        public Result classify(IntentUnderstandingRequest request, List<IntentCandidate> candidates) {
            IntentEvalCase evalCase = byText.get(request.userInput());
            if (evalCase == null) {
                return Result.failure(IntentClassifierFailure.PROVIDER_FAILURE);
            }
            if (evalCase.fixtureFailure() != IntentClassifierFailure.NONE) {
                return Result.failure(evalCase.fixtureFailure());
            }
            List<String> ranked = evalCase.acceptableCandidateIds().isEmpty()
                    ? candidateIds(candidates) : evalCase.acceptableCandidateIds();
            String primary = evalCase.allowedPrimaryNodeIds().stream().findFirst().orElse(null);
            IntentRouteOutcome classifierOutcome = evalCase.expectedOutcome()
                    == IntentRouteOutcome.EXECUTION_INFO_MISSING
                    ? IntentRouteOutcome.KNOWN_INTENT : evalCase.expectedOutcome();
            if (classifierOutcome == IntentRouteOutcome.KNOWN_INTENT && primary != null) {
                ranked = primaryFirst(primary, ranked, candidates);
            } else if (classifierOutcome == IntentRouteOutcome.MULTI_INTENT) {
                ranked = primaryFirst(primary, ranked, candidates);
            } else if (classifierOutcome == IntentRouteOutcome.AMBIGUOUS_ROUTE && ranked.size() < 2) {
                ranked = candidateIds(candidates).stream().limit(2).toList();
            } else if (classifierOutcome == IntentRouteOutcome.GENERAL_CHAT
                    || classifierOutcome == IntentRouteOutcome.OUT_OF_DOMAIN) {
                ranked = List.of();
            }
            return new Result(
                    classifierOutcome,
                    primary,
                    classifierOutcome == IntentRouteOutcome.MULTI_INTENT
                            ? evalCase.expectedSecondaryNodeIds() : List.of(),
                    ranked,
                    List.of(),
                    List.of("semantic_match"),
                    IntentClassifierFailure.NONE);
        }

        private List<String> primaryFirst(String primary,
                                          List<String> ranked,
                                          List<IntentCandidate> candidates) {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            if (primary != null) {
                ids.add(primary);
            }
            ids.addAll(ranked);
            ids.addAll(candidateIds(candidates));
            return List.copyOf(ids);
        }

        private List<String> candidateIds(List<IntentCandidate> candidates) {
            return candidates == null ? List.of() : candidates.stream()
                    .map(candidate -> candidate.node().getId()).toList();
        }
    }
}
