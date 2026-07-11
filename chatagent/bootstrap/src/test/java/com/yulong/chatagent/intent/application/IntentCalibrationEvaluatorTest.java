package com.yulong.chatagent.intent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class IntentCalibrationEvaluatorTest {

    @Test
    void shouldValidateReviewedDisjointCorpusAndFrozenHashes() {
        List<IntentPolicyEvaluationSupport.IntentEvalCase> calibration =
                IntentPolicyEvaluationSupport.loadCases("calibration");
        List<IntentPolicyEvaluationSupport.IntentEvalCase> holdout =
                IntentPolicyEvaluationSupport.loadCases("holdout");
        IntentPolicyEvaluationSupport.CorpusManifest manifest =
                IntentPolicyEvaluationSupport.loadManifest();
        IntentPolicyProfile profile = profile();

        assertThat(calibration).hasSize(150);
        assertThat(holdout).hasSize(150);
        assertThat(calibration.size() + holdout.size()).isGreaterThanOrEqualTo(300);
        assertThat(manifest.calibrationCases()).isEqualTo(calibration.size());
        assertThat(manifest.holdoutCases()).isEqualTo(holdout.size());
        assertThat(calibration).allMatch(evalCase -> "CURATED".equals(evalCase.reviewStatus()));
        assertThat(holdout).allMatch(evalCase -> "CURATED".equals(evalCase.reviewStatus()));

        assertDisjoint(calibration.stream().map(IntentPolicyEvaluationSupport.IntentEvalCase::caseId).toList(),
                holdout.stream().map(IntentPolicyEvaluationSupport.IntentEvalCase::caseId).toList());
        assertDisjoint(calibration.stream().map(IntentPolicyEvaluationSupport.IntentEvalCase::seedId).toList(),
                holdout.stream().map(IntentPolicyEvaluationSupport.IntentEvalCase::seedId).toList());
        assertDisjoint(calibration.stream().map(IntentPolicyEvaluationSupport::textFingerprint).toList(),
                holdout.stream().map(IntentPolicyEvaluationSupport::textFingerprint).toList());
        assertThat(new HashSet<>(calibration.stream()
                .map(IntentPolicyEvaluationSupport::textFingerprint).toList())).hasSameSizeAs(calibration);
        assertThat(new HashSet<>(holdout.stream()
                .map(IntentPolicyEvaluationSupport::textFingerprint).toList())).hasSameSizeAs(holdout);

        assertThat(count(holdout, evalCase -> evalCase.tags().contains("negative")))
                .isGreaterThanOrEqualTo(50);
        assertThat(count(holdout, evalCase -> evalCase.tags().contains("ambiguous")))
                .isGreaterThanOrEqualTo(40);
        assertThat(count(holdout, evalCase -> evalCase.tags().contains("high-risk")))
                .isGreaterThanOrEqualTo(30);
        assertThat(count(holdout, evalCase -> "EN".equals(evalCase.language())))
                .isGreaterThanOrEqualTo(40);
        assertThat(count(holdout, evalCase -> "ZH".equals(evalCase.language())))
                .isGreaterThanOrEqualTo(40);

        assertThat(manifest.corpusManifestHash()).isEqualTo(IntentPolicyEvaluationSupport.corpusHash());
        assertThat(manifest.treeFixtureManifestHash()).isEqualTo(IntentPolicyEvaluationSupport.treeFixtureHash());
        assertThat(profile.corpusManifestHash()).isEqualTo(manifest.corpusManifestHash());
        assertThat(profile.treeFixtureManifestHash()).isEqualTo(manifest.treeFixtureManifestHash());
        assertThat(profile.classifierModelId()).isEqualTo(manifest.classifierModelId());
        assertThat(profile.promptVersion()).isEqualTo(manifest.promptVersion());
        assertThat(profile.featureVersion()).isEqualTo(manifest.featureVersion());
        assertThat(profile.classifierTemperature()).isEqualTo(manifest.classifierTemperature());
        assertThat(profile.classifierMaxTokens()).isEqualTo(manifest.classifierMaxTokens());
    }

    @Test
    void shouldSelectFrozenProfileUsingCalibrationSplitOnly() {
        List<IntentPolicyEvaluationSupport.IntentEvalCase> calibration =
                IntentPolicyEvaluationSupport.loadCases("calibration");
        IntentTreeSnapshot snapshot = IntentPolicyEvaluationSupport.loadSnapshot();
        IntentPolicyProfile frozen = profile();
        List<ProfileScore> eligible = new ArrayList<>();

        for (double accept : List.of(1.0d, 1.2d, 1.4d, 1.6d)) {
            for (double clarify : List.of(0.20d, 0.35d, 0.50d, 0.80d)) {
                for (double gap : List.of(0.10d, 0.20d, 0.30d, 0.50d)) {
                    for (int classifierCap : List.of(6, 8)) {
                        for (int clarificationCap : List.of(3, 4)) {
                            IntentPolicyProfile candidate = IntentPolicyEvaluationSupport.withPolicyValues(
                                    frozen, accept, clarify, gap, classifierCap, clarificationCap, 2);
                            IntentPolicyEvaluationSupport.EvaluationReport report =
                                    IntentPolicyEvaluationSupport.evaluate(calibration, snapshot, candidate);
                            if (report.passesReleaseGates()) {
                                eligible.add(new ProfileScore(candidate, report));
                            }
                        }
                    }
                }
            }
        }

        assertThat(eligible).isNotEmpty();
        eligible.sort(this::compareProfileScore);
        IntentPolicyProfile selected = eligible.get(0).profile();
        assertThat(List.of(
                selected.acceptScore(), selected.clarifyScore(), selected.ambiguityGap(),
                (double) selected.maxClassifierCandidates(),
                (double) selected.maxClarificationCandidates(),
                (double) selected.minimumReviewedExamples()))
                .isEqualTo(List.of(
                        frozen.acceptScore(), frozen.clarifyScore(), frozen.ambiguityGap(),
                        (double) frozen.maxClassifierCandidates(),
                        (double) frozen.maxClarificationCandidates(),
                        (double) frozen.minimumReviewedExamples()));
    }

    @Test
    void shouldPassSealedHoldoutGatesWithRequiredSlicesAndDetailedReport() {
        List<IntentPolicyEvaluationSupport.IntentEvalCase> holdout =
                IntentPolicyEvaluationSupport.loadCases("holdout");

        IntentPolicyEvaluationSupport.EvaluationReport report =
                IntentPolicyEvaluationSupport.evaluate(
                        holdout, IntentPolicyEvaluationSupport.loadSnapshot(), profile());

        assertThat(report.passesReleaseGates())
                .as("failed outcomes: %s; reason codes: %s",
                        report.failedCaseOutcomes(), report.failedCaseReasonCodes())
                .isTrue();
        assertThat(report.knownLeafMacroF1()).isGreaterThanOrEqualTo(0.90d);
        report.recallByLeaf().forEach((leaf, recall) -> {
            if (report.supportByLeaf().getOrDefault(leaf, 0) >= 10) {
                assertThat(recall).as("recall for %s", leaf).isGreaterThanOrEqualTo(0.80d);
            }
        });
        assertThat(report.falseBusinessRouteRate()).isLessThanOrEqualTo(0.02d);
        assertThat(report.ambiguityRecall()).isGreaterThanOrEqualTo(0.90d);
        assertThat(report.unnecessaryClarificationRate()).isLessThanOrEqualTo(0.08d);
        assertThat(report.clarificationRecoveryAccuracy()).isGreaterThanOrEqualTo(0.90d);
        assertThat(report.newTopicReleaseAccuracy()).isGreaterThanOrEqualTo(0.95d);
        assertThat(report.multiIntentF1()).isGreaterThanOrEqualTo(0.85d);
        assertThat(report.sourceNeedAccuracy()).isGreaterThanOrEqualTo(0.92d);
        assertThat(report.highRiskWrongAutomaticExecutionCount()).isZero();
        assertThat(report.accuracyByLanguage()).containsKeys("EN", "ZH");
        assertThat(report.accuracyByDepth()).containsKeys("0", "2");
        assertThat(report.accuracyBySource()).containsKeys("NONE", "KB", "FILE", "WEB", "MIXED");
        assertThat(report.accuracyByRisk())
                .containsKeys("READ_ONLY", "WRITE_ACTION", "EXTERNAL_SIDE_EFFECT");
        assertThat(report.accuracyByOutcome()).containsKeys(
                "KNOWN_INTENT", "GENERAL_CHAT", "OUT_OF_DOMAIN", "AMBIGUOUS_ROUTE",
                "EXECUTION_INFO_MISSING", "MULTI_INTENT");
        assertThat(report.confusionMatrix()).containsKeys(
                "KNOWN_INTENT", "GENERAL_CHAT", "OUT_OF_DOMAIN", "AMBIGUOUS_ROUTE",
                "EXECUTION_INFO_MISSING", "MULTI_INTENT");
        assertThat(report.treeQualityFindingCodes()).isEmpty();
    }

    @Test
    void shouldWriteContentFreeMachineAndHumanReports() throws Exception {
        IntentPolicyProfile profile = profile();
        IntentTreeSnapshot snapshot = IntentPolicyEvaluationSupport.loadSnapshot();
        IntentPolicyEvaluationSupport.EvaluationReport calibration =
                IntentPolicyEvaluationSupport.evaluate(
                        IntentPolicyEvaluationSupport.loadCases("calibration"), snapshot, profile);
        IntentPolicyEvaluationSupport.EvaluationReport holdout =
                IntentPolicyEvaluationSupport.evaluate(
                        IntentPolicyEvaluationSupport.loadCases("holdout"), snapshot, profile);
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("reportVersion", "intent-eval-report-v1");
        bundle.put("selectionProcedure", "calibration-only-grid-search");
        bundle.put("profile", profile);
        bundle.put("corpusManifestHash", IntentPolicyEvaluationSupport.corpusHash());
        bundle.put("treeFixtureManifestHash", IntentPolicyEvaluationSupport.treeFixtureHash());
        bundle.put("calibration", calibration);
        bundle.put("sealedHoldout", holdout);

        Path output = Path.of("target", "intent-eval", "v1");
        Files.createDirectories(output);
        String machine = IntentPolicyEvaluationSupport.MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(bundle) + System.lineSeparator();
        Files.writeString(output.resolve("intent-eval-report-v1.json"), machine, StandardCharsets.UTF_8);
        String human = humanReport(profile, calibration, holdout);
        Files.writeString(output.resolve("intent-eval-report-v1.md"), human, StandardCharsets.UTF_8);

        assertThat(machine).contains("knownLeafMacroF1", "confusionMatrix", "accuracyByLanguage")
                .doesNotContain("currentUserText", "recentVisibleTurns", "provider reasoning");
        assertThat(human).contains("Calibration", "Sealed Holdout", "Source Need Accuracy")
                .doesNotContain("currentUserText", "provider reasoning");
        try (InputStream expectedJson = getClass().getResourceAsStream(
                "/intent-eval/v1/reports/intent-eval-report-v1.json");
             InputStream expectedMarkdown = getClass().getResourceAsStream(
                     "/intent-eval/v1/reports/intent-eval-report-v1.md")) {
            assertThat(expectedJson).isNotNull();
            assertThat(expectedMarkdown).isNotNull();
            assertThat(IntentPolicyEvaluationSupport.MAPPER.readTree(expectedJson))
                    .isEqualTo(IntentPolicyEvaluationSupport.MAPPER.readTree(machine));
            String expectedHuman = new String(expectedMarkdown.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            assertThat(human.replace("\r\n", "\n")).isEqualTo(expectedHuman);
        }
    }

    private int compareProfileScore(ProfileScore left, ProfileScore right) {
        int comparison = Double.compare(right.report().coverage(), left.report().coverage());
        if (comparison != 0) return comparison;
        comparison = Double.compare(left.report().falseBusinessRouteRate(),
                right.report().falseBusinessRouteRate());
        if (comparison != 0) return comparison;
        comparison = Double.compare(left.report().unnecessaryClarificationRate(),
                right.report().unnecessaryClarificationRate());
        if (comparison != 0) return comparison;
        comparison = Double.compare(right.profile().acceptScore(), left.profile().acceptScore());
        if (comparison != 0) return comparison;
        comparison = Double.compare(right.profile().clarifyScore(), left.profile().clarifyScore());
        if (comparison != 0) return comparison;
        comparison = Double.compare(right.profile().ambiguityGap(), left.profile().ambiguityGap());
        if (comparison != 0) return comparison;
        comparison = Integer.compare(left.profile().maxClassifierCandidates(),
                right.profile().maxClassifierCandidates());
        if (comparison != 0) return comparison;
        return Integer.compare(left.profile().maxClarificationCandidates(),
                right.profile().maxClarificationCandidates());
    }

    private IntentPolicyProfile profile() {
        IntentPolicyProperties properties = new IntentPolicyProperties();
        properties.setMode(IntentPolicyMode.ENFORCE);
        return new IntentPolicyProfileLoader(
                new DefaultResourceLoader(), new ObjectMapper(), properties,
                "deepseek-v4-flash").loadConfigured();
    }

    private String humanReport(IntentPolicyProfile profile,
                               IntentPolicyEvaluationSupport.EvaluationReport calibration,
                               IntentPolicyEvaluationSupport.EvaluationReport holdout) {
        return """
                # Intent Policy Evaluation v1

                Profile: `%s`  
                Classifier: `%s`  
                Prompt / feature: `%s` / `%s`  
                Frozen policy: accept `%.2f`, clarify `%.2f`, gap `%.2f`, classifier cap `%d`, clarification cap `%d`

                ## Calibration

                - Cases: %d
                - Route accuracy: %.4f
                - Known-leaf macro F1: %.4f
                - Source Need Accuracy: %.4f
                - Ambiguity recall: %.4f
                - High-risk wrong automatic executions: %d

                ## Sealed Holdout

                - Cases: %d
                - Route accuracy: %.4f
                - Known-leaf macro F1: %.4f
                - General/OOD false business-route rate: %.4f
                - Ambiguity recall: %.4f
                - Unnecessary clarification rate: %.4f
                - Clarification recovery accuracy: %.4f
                - New-topic release accuracy: %.4f
                - Multi-intent F1: %.4f
                - Source Need Accuracy: %.4f
                - Coverage / abstention: %.4f / %.4f
                - High-risk wrong automatic executions: %d

                Reports contain aggregate metrics, enum slices, hashes, reason codes, and safe case IDs only.
                """.formatted(
                profile.version(), profile.classifierModelId(), profile.promptVersion(), profile.featureVersion(),
                profile.acceptScore(), profile.clarifyScore(), profile.ambiguityGap(),
                profile.maxClassifierCandidates(), profile.maxClarificationCandidates(),
                calibration.totalCases(), calibration.routeAccuracy(), calibration.knownLeafMacroF1(),
                calibration.sourceNeedAccuracy(), calibration.ambiguityRecall(),
                calibration.highRiskWrongAutomaticExecutionCount(),
                holdout.totalCases(), holdout.routeAccuracy(), holdout.knownLeafMacroF1(),
                holdout.falseBusinessRouteRate(), holdout.ambiguityRecall(),
                holdout.unnecessaryClarificationRate(), holdout.clarificationRecoveryAccuracy(),
                holdout.newTopicReleaseAccuracy(), holdout.multiIntentF1(),
                holdout.sourceNeedAccuracy(), holdout.coverage(), holdout.abstentionRate(),
                holdout.highRiskWrongAutomaticExecutionCount());
    }

    private void assertDisjoint(List<String> first, List<String> second) {
        Set<String> overlap = new HashSet<>(first);
        overlap.retainAll(second);
        assertThat(overlap).isEmpty();
    }

    private long count(List<IntentPolicyEvaluationSupport.IntentEvalCase> cases,
                       Predicate<IntentPolicyEvaluationSupport.IntentEvalCase> predicate) {
        return cases.stream().filter(predicate).count();
    }

    private record ProfileScore(
            IntentPolicyProfile profile,
            IntentPolicyEvaluationSupport.EvaluationReport report
    ) {
    }
}
