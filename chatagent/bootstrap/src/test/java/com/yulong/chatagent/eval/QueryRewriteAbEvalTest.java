package com.yulong.chatagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.application.IntentTreeSnapshot;
import com.yulong.chatagent.intent.application.QueryRewriter;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2e: Query rewrite A/B evaluation.
 *
 * Uses the real {@link QueryRewriter} on multi-turn KB follow-up turns, then compares
 * retrieval quality before vs after rewriting on a lightweight lexical corpus assembled
 * from the eval intent tree plus the golden dialogues' assistant answers.
 *
 * Run: mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-query-rewrite \
 *      -Dtest=QueryRewriteAbEvalTest [-Deval.smoke=true]
 */
@Tag("eval-query-rewrite")
@SpringBootTest
@ActiveProfiles("local-gpu")
class QueryRewriteAbEvalTest {

    private static final int SMOKE_CASES_PER_DOMAIN = 1;

    @Autowired
    private QueryRewriter queryRewriter;

    private IntentTreeSnapshot snapshot;
    private Map<String, IntentResolution> kbResolutionsByPath;
    private List<RewriteCase> cases;
    private List<CorpusDoc> corpus;

    @BeforeEach
    void setUp() {
        snapshot = EvalTestTreeFactory.buildEnterpriseTree();
        kbResolutionsByPath = buildKbResolutions(snapshot);
        List<MultiturnGoldenDialogue> dialogues = GoldenDatasetLoader.loadMultiturnGolden();
        corpus = buildCorpus(dialogues, kbResolutionsByPath);
        List<RewriteCase> allCases = extractCases(dialogues, kbResolutionsByPath);
        if (Boolean.getBoolean("eval.smoke")) {
            cases = allCases.stream()
                    .collect(Collectors.groupingBy(RewriteCase::domain, LinkedHashMap::new, Collectors.toList()))
                    .values().stream()
                    .flatMap(list -> list.stream().limit(SMOKE_CASES_PER_DOMAIN))
                    .toList();
        } else {
            cases = allCases;
        }
    }

    @Test
    void evaluateQueryRewriteAb() throws Exception {
        List<CaseResult> results = new ArrayList<>();

        for (int i = 0; i < cases.size(); i++) {
            RewriteCase evalCase = cases.get(i);
            String rewritten = queryRewriter.rewrite(evalCase.originalQuery(), evalCase.resolution());

            List<String> controlRanking = rankDocs(evalCase.originalQuery(), corpus).stream()
                    .map(CorpusDoc::id)
                    .toList();
            List<String> treatmentRanking = rankDocs(rewritten, corpus).stream()
                    .map(CorpusDoc::id)
                    .toList();

            Set<String> relevant = Set.of(evalCase.relevantDocId());
            Map<String, Integer> grades = Map.of(evalCase.relevantDocId(), 3);

            CaseResult result = new CaseResult(
                    evalCase,
                    rewritten,
                    containsLoose(rewritten, evalCase.expectedAnchor()),
                    controlRanking,
                    treatmentRanking,
                    EvalMetrics.hitAtK(controlRanking, relevant, 1),
                    EvalMetrics.hitAtK(controlRanking, relevant, 3),
                    EvalMetrics.mrr(controlRanking, relevant),
                    EvalMetrics.ndcgAtK(controlRanking, grades, 3),
                    EvalMetrics.hitAtK(treatmentRanking, relevant, 1),
                    EvalMetrics.hitAtK(treatmentRanking, relevant, 3),
                    EvalMetrics.mrr(treatmentRanking, relevant),
                    EvalMetrics.ndcgAtK(treatmentRanking, grades, 3)
            );
            results.add(result);

            if ((i + 1) % 5 == 0) {
                Thread.sleep(1000);
            }
        }

        ArmMetrics control = aggregate(results, false);
        ArmMetrics treatment = aggregate(results, true);

        Map<String, Map<String, Object>> byDomain = new LinkedHashMap<>();
        results.stream()
                .collect(Collectors.groupingBy(result -> result.evalCase().domain(), LinkedHashMap::new, Collectors.toList()))
                .forEach((domain, domainResults) -> {
                    ArmMetrics controlDomain = aggregate(domainResults, false);
                    ArmMetrics treatmentDomain = aggregate(domainResults, true);
                    Map<String, Object> metrics = new LinkedHashMap<>();
                    metrics.put("count", domainResults.size());
                    metrics.put("controlHitAt1", round4(controlDomain.hitAt1()));
                    metrics.put("treatmentHitAt1", round4(treatmentDomain.hitAt1()));
                    metrics.put("controlMrr", round4(controlDomain.mrr()));
                    metrics.put("treatmentMrr", round4(treatmentDomain.mrr()));
                    metrics.put("controlNdcgAt3", round4(controlDomain.ndcgAt3()));
                    metrics.put("treatmentNdcgAt3", round4(treatmentDomain.ndcgAt3()));
                    metrics.put("ndcgLift", round4(treatmentDomain.ndcgAt3() - controlDomain.ndcgAt3()));
                    byDomain.put(domain, metrics);
                });

        long anchorPresentCount = results.stream().filter(CaseResult::anchorPresent).count();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "query-rewrite-ab");
        report.put("mode", "real-llm-rewriter");
        report.put("smokeMode", Boolean.getBoolean("eval.smoke"));
        report.put("casesEvaluated", results.size());
        report.put("anchorPresentRate", round4(ratio(anchorPresentCount, results.size())));
        report.put("control", Map.of(
                "avgHitAt1", round4(control.hitAt1()),
                "avgHitAt3", round4(control.hitAt3()),
                "avgMrr", round4(control.mrr()),
                "avgNdcgAt3", round4(control.ndcgAt3())
        ));
        report.put("treatment", Map.of(
                "avgHitAt1", round4(treatment.hitAt1()),
                "avgHitAt3", round4(treatment.hitAt3()),
                "avgMrr", round4(treatment.mrr()),
                "avgNdcgAt3", round4(treatment.ndcgAt3())
        ));
        report.put("delta", Map.of(
                "hitAt1Lift", round4(treatment.hitAt1() - control.hitAt1()),
                "hitAt3Lift", round4(treatment.hitAt3() - control.hitAt3()),
                "mrrLift", round4(treatment.mrr() - control.mrr()),
                "ndcgAt3Lift", round4(treatment.ndcgAt3() - control.ndcgAt3())
        ));
        report.put("byDomain", byDomain);
        report.put("perCase", results.stream().map(CaseResult::toMap).toList());

        Path reportPath = EvalReportWriter.writeReport("query-rewrite-ab-eval", report);
        System.out.println("=== Query Rewrite A/B Evaluation ===");
        System.out.println("Report: " + reportPath);
        System.out.println("Summary:\n" + new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(Map.of(
                        "cases", results.size(),
                        "anchorPresentRate", round4(ratio(anchorPresentCount, results.size())),
                        "controlNdcgAt3", round4(control.ndcgAt3()),
                        "treatmentNdcgAt3", round4(treatment.ndcgAt3()),
                        "ndcgLift", round4(treatment.ndcgAt3() - control.ndcgAt3())
                )));

        assertThat(results).isNotEmpty();
        assertThat(report.get("delta")).isNotNull();
    }

    private static Map<String, IntentResolution> buildKbResolutions(IntentTreeSnapshot snapshot) {
        Map<String, IntentResolution> map = new LinkedHashMap<>();
        for (IntentNodeDTO node : snapshot.getNodes()) {
            if (node.getIntentKind() != IntentKind.KB) {
                continue;
            }
            List<IntentNodeDTO> path = snapshot.pathTo(node.getId());
            IntentResolution resolution = new IntentResolution(
                    IntentKind.KB,
                    path,
                    snapshot.knowledgeBaseIdsForNode(node.getId()),
                    node.getScopePolicy(),
                    node.getAllowedTools(),
                    node.getSystemPromptOverride()
            );
            map.put(resolution.pathLabel(), resolution);
        }
        return map;
    }

    private static List<RewriteCase> extractCases(List<MultiturnGoldenDialogue> dialogues,
                                                  Map<String, IntentResolution> kbResolutionsByPath) {
        List<RewriteCase> result = new ArrayList<>();
        for (MultiturnGoldenDialogue dialogue : dialogues) {
            int caseIndex = 0;
            for (MultiturnGoldenDialogue.Turn turn : dialogue.turns()) {
                if (!"user".equalsIgnoreCase(turn.role())
                        || !StringUtils.hasText(turn.expectedCoreference())
                        || !StringUtils.hasText(turn.expectedIntentPath())) {
                    continue;
                }
                IntentResolution resolution = kbResolutionsByPath.get(turn.expectedIntentPath());
                if (resolution == null || resolution.kind() != IntentKind.KB) {
                    continue;
                }
                IntentNodeDTO leaf = resolution.path().get(resolution.path().size() - 1);
                caseIndex++;
                result.add(new RewriteCase(
                        dialogue.id() + "-coref-" + caseIndex,
                        dialogue.id(),
                        dialogue.domain(),
                        turn.content(),
                        turn.expectedIntentPath(),
                        leaf.getName(),
                        leaf.getId(),
                        resolution
                ));
            }
        }
        return result;
    }

    private static List<CorpusDoc> buildCorpus(List<MultiturnGoldenDialogue> dialogues,
                                               Map<String, IntentResolution> kbResolutionsByPath) {
        Map<String, StringBuilder> textByLeafId = new LinkedHashMap<>();
        Map<String, IntentResolution> resolutionByLeafId = new LinkedHashMap<>();

        for (IntentResolution resolution : kbResolutionsByPath.values()) {
            IntentNodeDTO leaf = resolution.path().get(resolution.path().size() - 1);
            StringBuilder builder = new StringBuilder();
            builder.append(resolution.pathLabel()).append('\n');
            if (StringUtils.hasText(leaf.getDescription())) {
                builder.append(leaf.getDescription()).append('\n');
            }
            if (leaf.getExamples() != null && !leaf.getExamples().isEmpty()) {
                builder.append(String.join(" ", leaf.getExamples())).append('\n');
            }
            textByLeafId.put(leaf.getId(), builder);
            resolutionByLeafId.put(leaf.getId(), resolution);
        }

        for (MultiturnGoldenDialogue dialogue : dialogues) {
            List<MultiturnGoldenDialogue.Turn> turns = dialogue.turns();
            for (int i = 0; i < turns.size() - 1; i++) {
                MultiturnGoldenDialogue.Turn current = turns.get(i);
                MultiturnGoldenDialogue.Turn next = turns.get(i + 1);
                if (!"user".equalsIgnoreCase(current.role())
                        || !"assistant".equalsIgnoreCase(next.role())
                        || !StringUtils.hasText(current.expectedIntentPath())) {
                    continue;
                }
                IntentResolution resolution = kbResolutionsByPath.get(current.expectedIntentPath());
                if (resolution == null) {
                    continue;
                }
                IntentNodeDTO leaf = resolution.path().get(resolution.path().size() - 1);
                textByLeafId.get(leaf.getId())
                        .append(next.content())
                        .append('\n');
            }
        }

        List<CorpusDoc> docs = new ArrayList<>();
        for (Map.Entry<String, StringBuilder> entry : textByLeafId.entrySet()) {
            IntentResolution resolution = resolutionByLeafId.get(entry.getKey());
            IntentNodeDTO leaf = resolution.path().get(resolution.path().size() - 1);
            docs.add(new CorpusDoc(
                    leaf.getId(),
                    resolution.pathLabel(),
                    leaf.getName(),
                    entry.getValue().toString().trim()
            ));
        }
        return docs;
    }

    private static List<CorpusDoc> rankDocs(String query, List<CorpusDoc> corpus) {
        String normalizedQuery = normalize(query);
        return corpus.stream()
                .sorted(Comparator
                        .comparingDouble((CorpusDoc doc) -> lexicalScore(normalizedQuery, doc)).reversed()
                        .thenComparing(CorpusDoc::pathLabel))
                .toList();
    }

    private static double lexicalScore(String normalizedQuery, CorpusDoc doc) {
        if (!StringUtils.hasText(normalizedQuery) || doc == null) {
            return 0.0d;
        }
        String normalizedLeaf = normalize(doc.leafName());
        String normalizedPath = normalize(doc.pathLabel());
        String normalizedText = normalize(doc.text());

        double score = 0.0d;
        if (normalizedQuery.contains(normalizedLeaf) || normalizedLeaf.contains(normalizedQuery)) {
            score += 1.2d;
        }
        score += overlapScore(normalizedQuery, normalizedPath) * 0.7d;
        score += overlapScore(normalizedQuery, normalizedText) * 1.1d;
        if (normalizedQuery.length() >= 2 && normalizedText.contains(normalizedQuery)) {
            score += 0.3d;
        }
        return score;
    }

    private static double overlapScore(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return 0.0d;
        }
        Set<String> leftUnits = splitUnits(left);
        Set<String> rightUnits = splitUnits(right);
        if (leftUnits.isEmpty() || rightUnits.isEmpty()) {
            return 0.0d;
        }
        Set<String> intersection = new LinkedHashSet<>(leftUnits);
        intersection.retainAll(rightUnits);
        Set<String> union = new LinkedHashSet<>(leftUnits);
        union.addAll(rightUnits);
        return union.isEmpty() ? 0.0d : (double) intersection.size() / union.size();
    }

    private static Set<String> splitUnits(String text) {
        Set<String> units = new LinkedHashSet<>();
        String compact = text.replace(" ", "");
        if (compact.length() <= 1) {
            if (!compact.isBlank()) {
                units.add(compact);
            }
            return units;
        }
        for (String word : text.split("\\s+")) {
            if (word.length() > 1) {
                units.add(word);
            }
        }
        for (int i = 0; i < compact.length() - 1; i++) {
            units.add(compact.substring(i, i + 2));
        }
        return units;
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}，。！？；：、“”‘’（）()\\[\\]{}]+", " ")
                .replaceAll("\\s+", " ");
    }

    private static boolean containsLoose(String text, String expected) {
        String normalizedText = normalize(text).replace(" ", "");
        String normalizedExpected = normalize(expected).replace(" ", "");
        return !normalizedExpected.isBlank() && normalizedText.contains(normalizedExpected);
    }

    private static ArmMetrics aggregate(List<CaseResult> results, boolean treatment) {
        return new ArmMetrics(
                results.stream().mapToDouble(result -> treatment ? result.treatmentHitAt1() : result.controlHitAt1()).average().orElse(0.0),
                results.stream().mapToDouble(result -> treatment ? result.treatmentHitAt3() : result.controlHitAt3()).average().orElse(0.0),
                results.stream().mapToDouble(result -> treatment ? result.treatmentMrr() : result.controlMrr()).average().orElse(0.0),
                results.stream().mapToDouble(result -> treatment ? result.treatmentNdcgAt3() : result.controlNdcgAt3()).average().orElse(0.0)
        );
    }

    private static double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0.0 : (double) numerator / denominator;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private record RewriteCase(
            String id,
            String dialogueId,
            String domain,
            String originalQuery,
            String expectedPath,
            String expectedAnchor,
            String relevantDocId,
            IntentResolution resolution
    ) {}

    private record CorpusDoc(
            String id,
            String pathLabel,
            String leafName,
            String text
    ) {}

    private record ArmMetrics(
            double hitAt1,
            double hitAt3,
            double mrr,
            double ndcgAt3
    ) {}

    private record CaseResult(
            RewriteCase evalCase,
            String rewrittenQuery,
            boolean anchorPresent,
            List<String> controlRanking,
            List<String> treatmentRanking,
            double controlHitAt1,
            double controlHitAt3,
            double controlMrr,
            double controlNdcgAt3,
            double treatmentHitAt1,
            double treatmentHitAt3,
            double treatmentMrr,
            double treatmentNdcgAt3
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", evalCase.id());
            map.put("dialogueId", evalCase.dialogueId());
            map.put("domain", evalCase.domain());
            map.put("query", evalCase.originalQuery());
            map.put("rewrittenQuery", rewrittenQuery);
            map.put("expectedPath", evalCase.expectedPath());
            map.put("expectedAnchor", evalCase.expectedAnchor());
            map.put("anchorPresent", anchorPresent);
            map.put("controlTop3", controlRanking.stream().limit(3).toList());
            map.put("treatmentTop3", treatmentRanking.stream().limit(3).toList());
            map.put("controlHitAt1", round4(controlHitAt1));
            map.put("treatmentHitAt1", round4(treatmentHitAt1));
            map.put("controlMrr", round4(controlMrr));
            map.put("treatmentMrr", round4(treatmentMrr));
            map.put("controlNdcgAt3", round4(controlNdcgAt3));
            map.put("treatmentNdcgAt3", round4(treatmentNdcgAt3));
            return map;
        }
    }
}
