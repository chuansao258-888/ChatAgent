package com.yulong.chatagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.application.IntentRouter;
import com.yulong.chatagent.intent.application.IntentRoutingResult;
import com.yulong.chatagent.intent.application.IntentTreeCacheManager;
import com.yulong.chatagent.intent.application.IntentTreeSnapshot;
import com.yulong.chatagent.intent.application.QueryRewriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2a: Multi-turn dialogue baseline evaluation with REAL LLM.
 *
 * Feeds each golden dialogue into IntentRouter + QueryRewriter turn-by-turn and measures:
 *   - intent path exact match vs expected hierarchical path
 *   - intent path domain match (first segment agrees)
 *   - average prefix depth (how many leading segments align)
 *   - coreference recall (rewritten query contains the expected antecedent)
 *   - topic-switch detection (router output changes across turns marked isTopicSwitch)
 *
 * This is a characterization test that emits a JSON report rather than a regression gate —
 * the intent tree in {@link EvalTestTreeFactory} and the golden paths are not fully aligned,
 * so low exact-match rates are expected and inform follow-up work on history-aware routing.
 *
 * Run: mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-multiturn \
 *      -Dtest=MultiturnDialogueEvalTest [-Deval.smoke=true]
 */
@Tag("eval-multiturn")
@SpringBootTest
@ActiveProfiles("local-gpu")
class MultiturnDialogueEvalTest {

    private static final int SMOKE_DIALOGUES_PER_DOMAIN = 1;

    @Autowired
    private IntentRouter intentRouter;

    @Autowired
    private QueryRewriter queryRewriter;

    private List<MultiturnGoldenDialogue> dialogues;

    @TestConfiguration
    static class EvalTestConfig {
        @Bean
        @Primary
        IntentTreeCacheManager evalIntentTreeCacheManager() {
            IntentTreeCacheManager delegate = mock(IntentTreeCacheManager.class);
            IntentTreeSnapshot tree = EvalTestTreeFactory.buildEnterpriseTree();
            when(delegate.loadActiveSnapshot(EvalTestTreeFactory.AGENT_ID)).thenReturn(tree);
            when(delegate.loadActiveSnapshot("assistant-1")).thenReturn(tree);
            return delegate;
        }
    }

    @BeforeEach
    void setUp() {
        List<MultiturnGoldenDialogue> all = GoldenDatasetLoader.loadMultiturnGolden();
        if (Boolean.getBoolean("eval.smoke")) {
            dialogues = all.stream()
                    .collect(Collectors.groupingBy(MultiturnGoldenDialogue::domain))
                    .values().stream()
                    .flatMap(list -> list.stream().limit(SMOKE_DIALOGUES_PER_DOMAIN))
                    .toList();
        } else {
            dialogues = all;
        }
    }

    @Test
    void evaluateMultiturnDialogues() throws Exception {
        List<DialogueResult> dialogueResults = new ArrayList<>();
        int llmCalls = 0;

        for (MultiturnGoldenDialogue dialogue : dialogues) {
            DialogueResult dr = new DialogueResult();
            dr.id = dialogue.id();
            dr.domain = dialogue.domain();
            String prevUserActualPath = null;
            IntentResolution prevUserResolution = null;

            int turnIdx = 0;
            for (MultiturnGoldenDialogue.Turn turn : dialogue.turns()) {
                if (!"user".equalsIgnoreCase(turn.role())) {
                    turnIdx++;
                    continue;
                }

                TurnResult tr = new TurnResult();
                tr.turnIdx = turnIdx;
                tr.query = turn.content();
                tr.expectedPath = turn.expectedIntentPath();
                tr.isTopicSwitch = turn.isTopicSwitch();
                tr.expectedCoreference = turn.expectedCoreference();

                IntentRoutingResult routing = intentRouter.routeWithHistory(
                        EvalTestTreeFactory.AGENT_ID, turn.content(), prevUserResolution);
                llmCalls++;

                IntentResolution resolution;
                if (routing.hasResolution()) {
                    resolution = routing.resolution();
                    tr.routerOutcome = "RESOLVED";
                    tr.actualPath = resolution.pathLabel();
                } else if (routing.requiresClarification()) {
                    resolution = null;
                    tr.routerOutcome = "CLARIFICATION";
                    tr.actualPath = null;
                } else {
                    resolution = null;
                    tr.routerOutcome = "NONE";
                    tr.actualPath = null;
                }

                if (tr.expectedPath != null && !tr.expectedPath.isBlank()) {
                    tr.exactMatch = tr.expectedPath.equals(tr.actualPath);
                    tr.prefixDepth = commonPrefixDepth(tr.expectedPath, tr.actualPath);
                    tr.domainMatch = tr.prefixDepth >= 1;
                }

                if (tr.expectedCoreference != null && !tr.expectedCoreference.isBlank()) {
                    // Fall back to the previous user turn's resolution when the current turn is
                    // unresolvable — this is what a history-aware rewriter would have to do, and
                    // it's the whole point of measuring coreference on multi-turn dialogues.
                    IntentResolution rewriteCtx = resolution != null ? resolution : prevUserResolution;
                    if (rewriteCtx != null) {
                        String rewritten = queryRewriter.rewrite(turn.content(), rewriteCtx);
                        llmCalls++;
                        tr.rewrittenQuery = rewritten;
                        tr.coreferenceContextSource = (resolution != null) ? "current-turn" : "prev-turn";
                        tr.coreferenceResolved = rewritten != null
                                && rewritten.toLowerCase(Locale.ROOT).contains(tr.expectedCoreference.toLowerCase(Locale.ROOT));
                    } else {
                        tr.coreferenceContextSource = "none";
                        tr.coreferenceResolved = false;
                    }
                }

                if (tr.isTopicSwitch && prevUserActualPath != null) {
                    tr.topicSwitchDetected = tr.actualPath != null && !tr.actualPath.equals(prevUserActualPath);
                }

                prevUserActualPath = tr.actualPath;
                if (resolution != null) {
                    prevUserResolution = resolution;
                }
                dr.turns.add(tr);

                // Rate limiting: mirror IntentRoutingIntegrationEvalTest cadence
                if (llmCalls % 5 == 0) {
                    Thread.sleep(1000);
                }
                turnIdx++;
            }
            dialogueResults.add(dr);
        }

        // ---- Aggregate metrics ----
        List<TurnResult> allTurns = dialogueResults.stream()
                .flatMap(dr -> dr.turns.stream())
                .toList();
        Map<String, Object> overall = computeMetrics(allTurns);

        Map<String, Map<String, Object>> byDomain = new LinkedHashMap<>();
        Map<String, List<TurnResult>> grouped = new LinkedHashMap<>();
        for (DialogueResult dr : dialogueResults) {
            grouped.computeIfAbsent(dr.domain, k -> new ArrayList<>()).addAll(dr.turns);
        }
        for (Map.Entry<String, List<TurnResult>> entry : grouped.entrySet()) {
            byDomain.put(entry.getKey(), computeMetrics(entry.getValue()));
        }

        // ---- Report shape ----
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "multiturn-eval");
        report.put("mode", "real-llm");
        report.put("smokeMode", Boolean.getBoolean("eval.smoke"));
        report.put("dialoguesEvaluated", dialogueResults.size());
        int userTurnsEvaluated = allTurns.size();
        report.put("userTurnsEvaluated", userTurnsEvaluated);
        report.put("llmCallCount", llmCalls);
        report.put("overall", overall);
        report.put("byDomain", byDomain);
        report.put("perDialogue", dialogueResults.stream().map(DialogueResult::toMap).toList());

        Path reportPath = EvalReportWriter.writeReport("multiturn-eval", report);
        System.out.println("=== Multi-turn Dialogue Evaluation ===");
        System.out.println("Report: " + reportPath);
        System.out.println("Overall:\n" + new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(overall));

        // Baseline characterization — sanity checks only, no accuracy thresholds.
        assertThat(userTurnsEvaluated).as("at least one user turn should have been evaluated").isGreaterThan(0);
        assertThat(overall.get("intentPathExactAccuracy")).as("exact accuracy present").isNotNull();
        assertThat(overall.get("coreferenceRecallTreatment")).as("coref treatment present").isNotNull();
        assertThat(overall.get("coreferenceRecallControl")).as("coref control present").isNotNull();
    }

    private static Map<String, Object> computeMetrics(List<TurnResult> turns) {
        long withExpected = turns.stream().filter(t -> t.expectedPath != null).count();
        long exactCorrect = turns.stream().filter(t -> t.expectedPath != null && t.exactMatch).count();
        long domainCorrect = turns.stream().filter(t -> t.expectedPath != null && t.domainMatch).count();
        double prefixDepthAvg = turns.stream()
                .filter(t -> t.expectedPath != null)
                .mapToInt(t -> t.prefixDepth)
                .average()
                .orElse(0.0);

        long corefExpected = turns.stream().filter(t -> t.expectedCoreference != null && !t.expectedCoreference.isBlank()).count();
        // Treatment: QueryRewriter falls back to the previous user turn's resolution when the
        // current turn is unresolvable (history-aware).
        long corefResolvedTreatment = turns.stream().filter(t -> Boolean.TRUE.equals(t.coreferenceResolved)).count();
        // Control: QueryRewriter runs only when the CURRENT turn itself produced a resolution —
        // i.e., a stateless rewriter with no history. "prev-turn" / "none" context sources count
        // as zero for the control arm even if the treatment rewriter succeeded.
        long corefResolvedControl = turns.stream()
                .filter(t -> "current-turn".equals(t.coreferenceContextSource) && Boolean.TRUE.equals(t.coreferenceResolved))
                .count();

        long switches = turns.stream().filter(t -> t.isTopicSwitch && t.topicSwitchDetected != null).count();
        long switchesDetected = turns.stream().filter(t -> Boolean.TRUE.equals(t.topicSwitchDetected)).count();

        double recallTreatment = EvalMetrics.accuracy(corefResolvedTreatment, corefExpected);
        double recallControl = EvalMetrics.accuracy(corefResolvedControl, corefExpected);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userTurns", turns.size());
        m.put("userTurnsWithExpectedPath", withExpected);
        m.put("intentPathExactAccuracy", round4(EvalMetrics.accuracy(exactCorrect, withExpected)));
        m.put("intentPathDomainAccuracy", round4(EvalMetrics.accuracy(domainCorrect, withExpected)));
        m.put("avgPrefixDepth", round4(prefixDepthAvg));
        m.put("coreferenceTurns", corefExpected);
        m.put("coreferenceRecallControl", round4(recallControl));
        m.put("coreferenceRecallTreatment", round4(recallTreatment));
        m.put("coreferenceAbLiftPp", round4((recallTreatment - recallControl) * 100));
        m.put("topicSwitchTurns", switches);
        m.put("topicSwitchDetection", round4(EvalMetrics.accuracy(switchesDetected, switches)));
        return m;
    }

    /** Number of leading " > "-separated segments that agree between two path labels. */
    private static int commonPrefixDepth(String expected, String actual) {
        if (expected == null || actual == null || expected.isBlank() || actual.isBlank()) {
            return 0;
        }
        String[] exp = expected.split("\\s*>\\s*");
        String[] act = actual.split("\\s*>\\s*");
        int n = Math.min(exp.length, act.length);
        int depth = 0;
        for (int i = 0; i < n; i++) {
            if (exp[i].equals(act[i])) {
                depth++;
            } else {
                break;
            }
        }
        return depth;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static class DialogueResult {
        String id;
        String domain;
        List<TurnResult> turns = new ArrayList<>();

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("domain", domain);
            m.put("turns", turns.stream().map(TurnResult::toMap).toList());
            return m;
        }
    }

    private static class TurnResult {
        int turnIdx;
        String query;
        String expectedPath;
        String actualPath;
        String routerOutcome;
        boolean exactMatch;
        boolean domainMatch;
        int prefixDepth;
        boolean isTopicSwitch;
        Boolean topicSwitchDetected;
        String expectedCoreference;
        String coreferenceContextSource;
        String rewrittenQuery;
        Boolean coreferenceResolved;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("turnIdx", turnIdx);
            m.put("query", query);
            m.put("expectedPath", expectedPath);
            m.put("actualPath", actualPath);
            m.put("routerOutcome", routerOutcome);
            m.put("exactMatch", exactMatch);
            m.put("domainMatch", domainMatch);
            m.put("prefixDepth", prefixDepth);
            m.put("isTopicSwitch", isTopicSwitch);
            m.put("topicSwitchDetected", topicSwitchDetected);
            m.put("expectedCoreference", expectedCoreference);
            m.put("coreferenceContextSource", coreferenceContextSource);
            m.put("rewrittenQuery", rewrittenQuery);
            m.put("coreferenceResolved", coreferenceResolved);
            return m;
        }
    }
}
