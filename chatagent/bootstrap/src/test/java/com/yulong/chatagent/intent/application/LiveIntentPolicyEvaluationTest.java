package com.yulong.chatagent.intent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.ChatClientRegistry;
import com.yulong.chatagent.chat.ChatModelRouter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("eval-real")
@EnabledIfSystemProperty(named = "chatagent.intent.eval.live", matches = "true")
class LiveIntentPolicyEvaluationTest {

    private static final int REQUIRED_RUNS = 3;

    @Test
    void shouldPassThreeIndependentFrozenHoldoutRuns() throws Exception {
        List<IntentPolicyEvaluationSupport.IntentEvalCase> holdout =
                IntentPolicyEvaluationSupport.loadCases("holdout");
        IntentTreeSnapshot snapshot = IntentPolicyEvaluationSupport.loadSnapshot();
        IntentPolicyProfileLoader profileLoader = profileLoader();
        IntentPolicyProfile profile = profileLoader.loadConfigured();
        StructuredIntentClassifier classifier = liveClassifier(profile.classifierModelId());
        List<IntentPolicyEvaluationSupport.EvaluationReport> reports = new ArrayList<>();

        for (int run = 0; run < REQUIRED_RUNS; run++) {
            IntentPolicyEvaluationSupport.EvaluationReport report =
                    IntentPolicyEvaluationSupport.evaluate(holdout, snapshot, profile, classifier);
            assertThat(report.passesReleaseGates())
                    .as("live run %s failed outcomes=%s reasons=%s", run + 1,
                            report.failedCaseOutcomes(), report.failedCaseReasonCodes())
                    .isTrue();
            assertThat(report.highRiskWrongAutomaticExecutionCount()).isZero();
            reports.add(report);
        }

        double stability = clearTurnStability(holdout, reports);
        assertThat(stability).isGreaterThanOrEqualTo(0.95d);
        writeSafeReport(profile, reports, stability);
    }

    private IntentPolicyProfileLoader profileLoader() {
        return new IntentPolicyProfileLoader(
                new DefaultResourceLoader(),
                new ObjectMapper(),
                new IntentPolicyProperties(),
                "deepseek-v4-flash");
    }

    private StructuredIntentClassifier liveClassifier(String modelId) {
        String apiKey = requiredConfig("CHATAGENT_DEEPSEEK_API_KEY");
        String baseUrl = configValue("CHATAGENT_DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        DeepSeekApi api = DeepSeekApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        DeepSeekChatModel model = DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .defaultOptions(DeepSeekChatOptions.builder().model(modelId).build())
                .build();
        ChatClientRegistry registry = new ChatClientRegistry(Map.of(modelId, ChatClient.create(model)));
        ChatModelRouter router = new ChatModelRouter(registry, modelId);
        return new StructuredIntentClassifier(
                new PromptLoader(new DefaultResourceLoader()),
                router,
                new ObjectMapper(),
                modelId);
    }

    private String requiredConfig(String name) {
        String value = configValue(name, "");
        if (value.isBlank()) {
            throw new IllegalStateException(name + " is required for live intent evaluation");
        }
        return value;
    }

    private String configValue(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = System.getProperty(name);
        }
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private double clearTurnStability(
            List<IntentPolicyEvaluationSupport.IntentEvalCase> cases,
            List<IntentPolicyEvaluationSupport.EvaluationReport> reports) {
        List<String> ids = cases.stream()
                .filter(evalCase -> evalCase.tags().contains("clear-known")
                        || evalCase.tags().contains("general-chat"))
                .map(IntentPolicyEvaluationSupport.IntentEvalCase::caseId)
                .toList();
        long stable = ids.stream().filter(caseId -> {
            String first = reports.get(0).outcomeByCaseId().get(caseId);
            return reports.stream().allMatch(report ->
                    java.util.Objects.equals(first, report.outcomeByCaseId().get(caseId)));
        }).count();
        return ids.isEmpty() ? 1.0d : (double) stable / ids.size();
    }

    private void writeSafeReport(IntentPolicyProfile profile,
                                 List<IntentPolicyEvaluationSupport.EvaluationReport> reports,
                                 double stability) throws Exception {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("reportVersion", "intent-live-eval-v1");
        output.put("profileVersion", profile.version());
        output.put("classifierModelId", profile.classifierModelId());
        output.put("promptVersion", profile.promptVersion());
        output.put("featureVersion", profile.featureVersion());
        output.put("runCount", reports.size());
        output.put("clearTurnOutcomeStability", stability);
        output.put("runs", reports);
        Path directory = Path.of("target", "intent-eval", "v1");
        Files.createDirectories(directory);
        String json = IntentPolicyEvaluationSupport.MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(output) + System.lineSeparator();
        Files.writeString(directory.resolve("intent-live-eval-v1.json"), json, StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("currentUserText", "recentVisibleTurns", "provider reasoning");
    }
}
