package com.yulong.chatagent.eval;

import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.application.IntentTreeSnapshot;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * Contextual enrichment ROI A/B evaluation.
 *
 * <p>Compares retrieval quality on two corpus variants built from the same intent tree:
 * <ul>
 *   <li>Control — raw chunk text (leaf name + description + examples only)</li>
 *   <li>Treatment — chunk text + contextual enrichment prefix (domain path, category description,
 *       section context), simulating what {@code LlmContextualChunkEnricher} would produce</li>
 * </ul>
 *
 * <p>Queries come from the golden multi-turn dialogues. Lexical scoring is used as a retrieval proxy
 * (same approach as {@link QueryRewriteAbEvalTest}), so no external infrastructure is required.
 *
 * <p>Run: mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-contextual-enrichment -Dtest=ContextualEnrichmentAbEvalTest
 */
@Tag("eval-contextual-enrichment")
class ContextualEnrichmentAbEvalTest {

    private IntentTreeSnapshot snapshot;
    private Map<String, IntentResolution> kbResolutionsByPath;
    private List<QueryCase> cases;
    private List<CorpusDoc> controlCorpus;
    private List<CorpusDoc> treatmentCorpus;

    @BeforeEach
    void setUp() {
        snapshot = EvalTestTreeFactory.buildEnterpriseTree();
        kbResolutionsByPath = buildKbResolutions(snapshot);
        List<MultiturnGoldenDialogue> dialogues = GoldenDatasetLoader.loadMultiturnGolden();
        controlCorpus = buildCorpus(dialogues, kbResolutionsByPath, false);
        treatmentCorpus = buildCorpus(dialogues, kbResolutionsByPath, true);
        cases = extractQueryCases(dialogues, kbResolutionsByPath);
    }

    @Test
    void evaluateContextualEnrichmentAb() throws Exception {
        List<CaseResult> results = new ArrayList<>();

        for (QueryCase evalCase : cases) {
            List<String> controlRanking = rankDocs(evalCase.query(), controlCorpus).stream()
                    .map(CorpusDoc::id).toList();
            List<String> treatmentRanking = rankDocs(evalCase.query(), treatmentCorpus).stream()
                    .map(CorpusDoc::id).toList();

            Set<String> relevant = Set.of(evalCase.relevantDocId());
            Map<String, Integer> grades = Map.of(evalCase.relevantDocId(), 3);

            results.add(new CaseResult(
                    evalCase,
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
            ));
        }

        ArmMetrics control = aggregate(results, false);
        ArmMetrics treatment = aggregate(results, true);

        Map<String, Map<String, Object>> byCategory = new LinkedHashMap<>();
        results.stream()
                .collect(Collectors.groupingBy(r -> r.evalCase().category(), LinkedHashMap::new, Collectors.toList()))
                .forEach((category, catResults) -> {
                    ArmMetrics catCtrl = aggregate(catResults, false);
                    ArmMetrics catTreat = aggregate(catResults, true);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("count", catResults.size());
                    m.put("controlHitAt1", round4(catCtrl.hitAt1()));
                    m.put("treatmentHitAt1", round4(catTreat.hitAt1()));
                    m.put("controlNdcgAt3", round4(catCtrl.ndcgAt3()));
                    m.put("treatmentNdcgAt3", round4(catTreat.ndcgAt3()));
                    m.put("ndcgLift", round4(catTreat.ndcgAt3() - catCtrl.ndcgAt3()));
                    byCategory.put(category, m);
                });

        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
        results.stream()
                .collect(Collectors.groupingBy(r -> r.evalCase().queryType(), LinkedHashMap::new, Collectors.toList()))
                .forEach((type, typeResults) -> {
                    ArmMetrics typeCtrl = aggregate(typeResults, false);
                    ArmMetrics typeTreat = aggregate(typeResults, true);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("count", typeResults.size());
                    m.put("controlHitAt1", round4(typeCtrl.hitAt1()));
                    m.put("treatmentHitAt1", round4(typeTreat.hitAt1()));
                    m.put("controlNdcgAt3", round4(typeCtrl.ndcgAt3()));
                    m.put("treatmentNdcgAt3", round4(typeTreat.ndcgAt3()));
                    m.put("ndcgLift", round4(typeTreat.ndcgAt3() - typeCtrl.ndcgAt3()));
                    byType.put(type, m);
                });

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "contextual-enrichment-ab");
        report.put("casesEvaluated", results.size());
        report.put("controlCorpusSize", controlCorpus.size());
        report.put("treatmentCorpusSize", treatmentCorpus.size());
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
        report.put("byCategory", byCategory);
        report.put("byQueryType", byType);
        report.put("perCase", results.stream().map(CaseResult::toMap).toList());

        Path reportPath = EvalReportWriter.writeReport("contextual-enrichment-ab-eval", report);

        System.out.println("\n=== Contextual Enrichment A/B Evaluation ===");
        System.out.printf("Cases: %d, Corpus docs: %d%n", results.size(), controlCorpus.size());
        System.out.printf("%-20s  Hit@1   Hit@3   MRR     NDCG@3%n", "Arm");
        System.out.printf("%-20s  %.4f  %.4f  %.4f  %.4f%n", "Control (raw)",
                control.hitAt1(), control.hitAt3(), control.mrr(), control.ndcgAt3());
        System.out.printf("%-20s  %.4f  %.4f  %.4f  %.4f%n", "Treatment (enriched)",
                treatment.hitAt1(), treatment.hitAt3(), treatment.mrr(), treatment.ndcgAt3());
        System.out.printf("%-20s  %+.4f  %+.4f  %+.4f  %+.4f%n", "Delta",
                treatment.hitAt1() - control.hitAt1(), treatment.hitAt3() - control.hitAt3(),
                treatment.mrr() - control.mrr(), treatment.ndcgAt3() - control.ndcgAt3());
        System.out.println("\nBy query type:");
        byType.forEach((type, m) -> System.out.printf("  %-12s — %d cases, NDCG lift: %+.4f%n",
                type, m.get("count"), m.get("ndcgLift")));
        System.out.println("\nReport: " + reportPath);

        assertThat(results).isNotEmpty();
        assertThat(treatment.ndcgAt3()).as("enrichment should not degrade retrieval")
                .isGreaterThanOrEqualTo(control.ndcgAt3());
    }

    // ---- corpus building ----

    private static List<CorpusDoc> buildCorpus(List<MultiturnGoldenDialogue> dialogues,
                                               Map<String, IntentResolution> kbResolutionsByPath,
                                               boolean enriched) {
        Map<String, StringBuilder> textByLeafId = new LinkedHashMap<>();
        Map<String, IntentResolution> resolutionByLeafId = new LinkedHashMap<>();

        for (IntentResolution resolution : kbResolutionsByPath.values()) {
            IntentNodeDTO leaf = resolution.path().get(resolution.path().size() - 1);
            StringBuilder builder = new StringBuilder();

            if (enriched) {
                builder.append("上下文：本文档属于「").append(resolution.pathLabel()).append("」。");
                for (int i = 0; i < resolution.path().size() - 1; i++) {
                    IntentNodeDTO ancestor = resolution.path().get(i);
                    if (StringUtils.hasText(ancestor.getDescription())) {
                        builder.append(ancestor.getName()).append("：").append(ancestor.getDescription()).append("。");
                    }
                }
                builder.append("\n\n");
            }

            builder.append(leaf.getName()).append('\n');
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
                        || !StringUtils.hasText(current.expectedIntentPath())) continue;
                IntentResolution resolution = kbResolutionsByPath.get(current.expectedIntentPath());
                if (resolution == null) continue;
                IntentNodeDTO leaf = resolution.path().get(resolution.path().size() - 1);
                StringBuilder sb = textByLeafId.get(leaf.getId());
                if (sb != null) sb.append(next.content()).append('\n');
            }
        }

        List<CorpusDoc> docs = new ArrayList<>();
        for (Map.Entry<String, StringBuilder> entry : textByLeafId.entrySet()) {
            IntentResolution resolution = resolutionByLeafId.get(entry.getKey());
            IntentNodeDTO leaf = resolution.path().get(resolution.path().size() - 1);
            docs.add(new CorpusDoc(leaf.getId(), resolution.pathLabel(), leaf.getName(),
                    entry.getValue().toString().trim()));
        }
        return docs;
    }

    // ---- query extraction ----

    private static List<QueryCase> extractQueryCases(List<MultiturnGoldenDialogue> dialogues,
                                                     Map<String, IntentResolution> kbResolutionsByPath) {
        List<QueryCase> result = new ArrayList<>();
        for (MultiturnGoldenDialogue dialogue : dialogues) {
            int idx = 0;
            for (MultiturnGoldenDialogue.Turn turn : dialogue.turns()) {
                if (!"user".equalsIgnoreCase(turn.role())
                        || !StringUtils.hasText(turn.expectedIntentPath())) continue;
                IntentResolution resolution = kbResolutionsByPath.get(turn.expectedIntentPath());
                if (resolution == null || resolution.kind() != IntentKind.KB) continue;
                IntentNodeDTO leaf = resolution.path().get(resolution.path().size() - 1);
                String queryType = StringUtils.hasText(turn.expectedCoreference()) ? "coreference" : "direct";
                String category = resolution.path().size() >= 2
                        ? resolution.path().get(0).getName() : "unknown";
                idx++;
                result.add(new QueryCase(
                        dialogue.id() + "-q" + idx,
                        dialogue.domain(),
                        category,
                        queryType,
                        turn.content(),
                        leaf.getId()
                ));
            }
        }
        return result;
    }

    // ---- lexical retrieval (same approach as QueryRewriteAbEvalTest) ----

    private static List<CorpusDoc> rankDocs(String query, List<CorpusDoc> corpus) {
        String normalized = normalize(query);
        return corpus.stream()
                .sorted(Comparator
                        .comparingDouble((CorpusDoc doc) -> lexicalScore(normalized, doc)).reversed()
                        .thenComparing(CorpusDoc::pathLabel))
                .toList();
    }

    private static double lexicalScore(String normalizedQuery, CorpusDoc doc) {
        if (!StringUtils.hasText(normalizedQuery)) return 0.0;
        String normLeaf = normalize(doc.leafName());
        String normPath = normalize(doc.pathLabel());
        String normText = normalize(doc.text());

        double score = 0.0;
        if (normalizedQuery.contains(normLeaf) || normLeaf.contains(normalizedQuery)) score += 1.2;
        score += overlapScore(normalizedQuery, normPath) * 0.7;
        score += overlapScore(normalizedQuery, normText) * 1.1;
        if (normalizedQuery.length() >= 2 && normText.contains(normalizedQuery)) score += 0.3;
        return score;
    }

    private static double overlapScore(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) return 0.0;
        Set<String> l = splitUnits(left), r = splitUnits(right);
        if (l.isEmpty() || r.isEmpty()) return 0.0;
        Set<String> intersection = new LinkedHashSet<>(l);
        intersection.retainAll(r);
        Set<String> union = new LinkedHashSet<>(l);
        union.addAll(r);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static Set<String> splitUnits(String text) {
        Set<String> units = new LinkedHashSet<>();
        String compact = text.replace(" ", "");
        if (compact.length() <= 1) { if (!compact.isBlank()) units.add(compact); return units; }
        for (String w : text.split("\\s+")) { if (w.length() > 1) units.add(w); }
        for (int i = 0; i < compact.length() - 1; i++) units.add(compact.substring(i, i + 2));
        return units;
    }

    private static String normalize(String v) {
        if (!StringUtils.hasText(v)) return "";
        return v.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\uFF0C\\u3002\\uFF01\\uFF1F\\uFF1B\\uFF1A\\u3001\\u201C\\u201D\\u2018\\u2019\\uFF08\\uFF09()\\[\\]{}]+", " ")
                .replaceAll("\\s+", " ");
    }

    // ---- helpers ----

    private static Map<String, IntentResolution> buildKbResolutions(IntentTreeSnapshot snapshot) {
        Map<String, IntentResolution> map = new LinkedHashMap<>();
        for (IntentNodeDTO node : snapshot.getNodes()) {
            if (node.getIntentKind() != IntentKind.KB) continue;
            List<IntentNodeDTO> path = snapshot.pathTo(node.getId());
            IntentResolution resolution = new IntentResolution(
                    IntentKind.KB, path,
                    snapshot.knowledgeBaseIdsForNode(node.getId()),
                    node.getScopePolicy(), node.getAllowedTools(), node.getSystemPromptOverride());
            map.put(resolution.pathLabel(), resolution);
        }
        return map;
    }

    private static ArmMetrics aggregate(List<CaseResult> results, boolean treatment) {
        return new ArmMetrics(
                results.stream().mapToDouble(r -> treatment ? r.treatmentHitAt1 : r.controlHitAt1).average().orElse(0),
                results.stream().mapToDouble(r -> treatment ? r.treatmentHitAt3 : r.controlHitAt3).average().orElse(0),
                results.stream().mapToDouble(r -> treatment ? r.treatmentMrr : r.controlMrr).average().orElse(0),
                results.stream().mapToDouble(r -> treatment ? r.treatmentNdcgAt3 : r.controlNdcgAt3).average().orElse(0)
        );
    }

    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    // ---- data carriers ----

    private record QueryCase(String id, String domain, String category, String queryType,
                             String query, String relevantDocId) {}

    private record CorpusDoc(String id, String pathLabel, String leafName, String text) {}

    private record ArmMetrics(double hitAt1, double hitAt3, double mrr, double ndcgAt3) {}

    private record CaseResult(
            QueryCase evalCase,
            List<String> controlRanking, List<String> treatmentRanking,
            double controlHitAt1, double controlHitAt3, double controlMrr, double controlNdcgAt3,
            double treatmentHitAt1, double treatmentHitAt3, double treatmentMrr, double treatmentNdcgAt3
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", evalCase.id());
            m.put("domain", evalCase.domain());
            m.put("category", evalCase.category());
            m.put("queryType", evalCase.queryType());
            m.put("query", evalCase.query());
            m.put("controlTop3", controlRanking.stream().limit(3).toList());
            m.put("treatmentTop3", treatmentRanking.stream().limit(3).toList());
            m.put("controlNdcgAt3", round4(controlNdcgAt3));
            m.put("treatmentNdcgAt3", round4(treatmentNdcgAt3));
            m.put("ndcgLift", round4(treatmentNdcgAt3 - controlNdcgAt3));
            return m;
        }
    }
}
