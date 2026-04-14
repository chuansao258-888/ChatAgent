package com.yulong.chatagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yulong.chatagent.TestPromptLoader;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.application.IntentRouter;
import com.yulong.chatagent.intent.application.IntentRoutingResult;
import com.yulong.chatagent.intent.application.IntentTreeCacheManager;
import com.yulong.chatagent.intent.application.IntentTreeSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Intent routing evaluation test.
 * Loads intent-golden.json, runs IntentRouter with per-query mock setup, computes accuracy/F1 metrics.
 */
@Tag("eval-intent")
class IntentRoutingEvalTest {

    private IntentTreeSnapshot snapshot;
    private List<IntentGoldenEntry> goldenEntries;

    @BeforeEach
    void setUp() {
        snapshot = EvalTestTreeFactory.buildEnterpriseTree();
        boolean smoke = Boolean.getBoolean("eval.smoke");
        goldenEntries = smoke ? GoldenDatasetLoader.loadIntentGoldenSmoke() : GoldenDatasetLoader.loadIntentGolden();
    }

    @Test
    void evaluateIntentRoutingAccuracy() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
        when(meterRegistryProvider.getIfAvailable()).thenReturn(null);

        List<EntryResult> results = new ArrayList<>();

        for (IntentGoldenEntry entry : goldenEntries) {
            IntentTreeCacheManager cacheManager = mock(IntentTreeCacheManager.class);
            ChatModelRouter modelRouter = mock(ChatModelRouter.class);
            when(cacheManager.loadActiveSnapshot(EvalTestTreeFactory.AGENT_ID)).thenReturn(snapshot);

            if (!entry.heuristicShouldSuffice()) {
                ChatClient chatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
                when(modelRouter.route("classifier-model")).thenReturn(chatClient);
                String llmContent = getLlmResponseForEntry(entry);
                lenient().when(chatClient.prompt(anyString()).call().content()).thenReturn(llmContent);
            }

            IntentRouter router = new IntentRouter(
                    TestPromptLoader.create(), cacheManager, modelRouter,
                    0.45d, 0.2d, 2, "classifier-model", meterRegistryProvider
            );

            IntentRoutingResult result = router.route(EvalTestTreeFactory.AGENT_ID, entry.query());
            results.add(evaluateEntry(entry, result));
        }

        // Compute aggregate metrics
        long correctNodeId = results.stream().filter(r -> r.entry.expectedNodeId() != null && r.nodeIdMatch).count();
        long correctKind = results.stream().filter(r -> !"NONE".equals(r.entry.expectedKind()) && r.kindMatch).count();
        long totalWithNodeId = results.stream().filter(r -> r.entry.expectedNodeId() != null).count();
        long totalWithKind = results.stream().filter(r -> !"NONE".equals(r.entry.expectedKind())).count();

        double nodeAccuracy = EvalMetrics.accuracy(correctNodeId, totalWithNodeId);
        double kindAccuracy = EvalMetrics.accuracy(correctKind, totalWithKind);

        // Clarification metrics
        long clarifyTp = results.stream()
                .filter(r -> r.entry.expectedClarification() && r.triggeredClarification).count();
        long clarifyAllTriggered = results.stream().filter(r -> r.triggeredClarification).count();
        long clarifyAllExpected = results.stream().filter(r -> r.entry.expectedClarification()).count();
        double clarifyPrecision = clarifyAllTriggered == 0 ? 0 : (double) clarifyTp / clarifyAllTriggered;
        double clarifyRecall = clarifyAllExpected == 0 ? 0 : (double) clarifyTp / clarifyAllExpected;
        double clarifyF1 = EvalMetrics.f1(clarifyPrecision, clarifyRecall);

        // Out-of-scope metrics
        long oosTp = results.stream()
                .filter(r -> r.entry.expectedOutOfScope() && r.detectedNone).count();
        long oosFp = results.stream()
                .filter(r -> !r.entry.expectedOutOfScope() && r.detectedNone).count();
        long oosFn = results.stream()
                .filter(r -> r.entry.expectedOutOfScope() && !r.detectedNone).count();
        double oosPrecision = (oosTp + oosFp) == 0 ? 0 : (double) oosTp / (oosTp + oosFp);
        double oosRecall = (oosTp + oosFn) == 0 ? 0 : (double) oosTp / (oosTp + oosFn);
        double oosF1 = EvalMetrics.f1(oosPrecision, oosRecall);

        // Heuristic hit rate
        long heuristicEntries = results.stream()
                .filter(r -> r.entry.heuristicShouldSuffice()).count();
        long heuristicCorrect = results.stream()
                .filter(r -> r.entry.heuristicShouldSuffice() && r.nodeIdMatch && r.kindMatch)
                .count();
        double heuristicHitRate = EvalMetrics.accuracy(heuristicCorrect, heuristicEntries);

        // End-to-end accuracy
        long endToEndCorrect = results.stream()
                .filter(r -> {
                    if (r.entry.expectedOutOfScope()) return r.detectedNone;
                    if (r.entry.expectedClarification()) return r.triggeredClarification;
                    return r.nodeIdMatch && r.kindMatch;
                }).count();
        double endToEndAccuracy = EvalMetrics.accuracy(endToEndCorrect, results.size());

        // Build report
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("nodeAccuracy", round4(nodeAccuracy));
        metrics.put("kindAccuracy", round4(kindAccuracy));
        metrics.put("endToEndAccuracy", round4(endToEndAccuracy));
        metrics.put("clarificationPrecision", round4(clarifyPrecision));
        metrics.put("clarificationRecall", round4(clarifyRecall));
        metrics.put("clarificationF1", round4(clarifyF1));
        metrics.put("outOfScopePrecision", round4(oosPrecision));
        metrics.put("outOfScopeRecall", round4(oosRecall));
        metrics.put("outOfScopeF1", round4(oosF1));
        metrics.put("heuristicHitRate", round4(heuristicHitRate));
        metrics.put("totalEntries", results.size());

        List<Map<String, Object>> details = results.stream()
                .map(r -> {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("id", r.entry.id());
                    detail.put("query", r.entry.query());
                    detail.put("category", r.entry.category());
                    detail.put("nodeIdMatch", r.nodeIdMatch);
                    detail.put("kindMatch", r.kindMatch);
                    detail.put("pass", r.entry.expectedOutOfScope() ? r.detectedNone :
                            r.entry.expectedClarification() ? r.triggeredClarification :
                            r.nodeIdMatch && r.kindMatch);
                    return detail;
                }).toList();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "intent-routing");
        report.put("metrics", metrics);
        report.put("details", details);
        report.put("goldenEntryCount", goldenEntries.size());
        report.put("smokeMode", Boolean.getBoolean("eval.smoke"));

        Path reportPath = EvalReportWriter.writeReport("intent-eval", report);
        System.out.println("Intent routing evaluation report written to: " + reportPath);
        System.out.println("Metrics: " + new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(metrics));

        // Assert minimum quality thresholds
        assertThat(endToEndAccuracy)
                .as("End-to-end accuracy should be >= 30% (mock mode baseline)")
                .isGreaterThanOrEqualTo(0.30);
    }

    private String getLlmResponseForEntry(IntentGoldenEntry entry) {
        if (entry.expectedOutOfScope()) return "NONE";
        if (entry.expectedClarification()) return "AMBIGUOUS";
        return entry.expectedNodeId() != null ? entry.expectedNodeId() : "NONE";
    }

    private EntryResult evaluateEntry(IntentGoldenEntry entry, IntentRoutingResult result) {
        EntryResult er = new EntryResult();
        er.entry = entry;

        if (entry.expectedOutOfScope()) {
            er.detectedNone = !result.hasResolution() && !result.requiresClarification();
            er.nodeIdMatch = true;
            er.kindMatch = true;
        } else if (entry.expectedClarification()) {
            er.triggeredClarification = result.requiresClarification();
            er.nodeIdMatch = true;
            er.kindMatch = true;
        } else {
            if (result.hasResolution()) {
                IntentResolution resolution = result.resolution();
                String resolvedNodeId = resolution.path().isEmpty() ? null :
                        resolution.path().get(resolution.path().size() - 1).getId();
                er.nodeIdMatch = entry.expectedNodeId() != null && entry.expectedNodeId().equals(resolvedNodeId);
                er.kindMatch = entry.expectedKind() != null && entry.expectedKind().equals(resolution.kind().name());
            } else {
                er.nodeIdMatch = false;
                er.kindMatch = false;
            }
        }

        return er;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static class EntryResult {
        IntentGoldenEntry entry;
        boolean nodeIdMatch;
        boolean kindMatch;
        boolean triggeredClarification;
        boolean detectedNone;
    }
}
