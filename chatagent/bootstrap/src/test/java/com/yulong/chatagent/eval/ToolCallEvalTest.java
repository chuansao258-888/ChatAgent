package com.yulong.chatagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yulong.chatagent.agent.AgentMessageBridge;
import com.yulong.chatagent.agent.ChatAgent;
import com.yulong.chatagent.agent.DecisionVisibility;
import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.intent.application.ConversationTurnPreparationService;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.application.IntentTreeCacheManager;
import com.yulong.chatagent.intent.application.IntentTreeSnapshot;
import com.yulong.chatagent.intent.application.TurnPreparationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2f: Tool-call evaluation with real LLM planning plus deterministic fake tools.
 *
 * The harness measures:
 *   - tool selection accuracy against golden expectedTools
 *   - intent-kind accuracy from ConversationTurnPreparationService
 *   - whether the run stays within expectedMaxSteps
 *   - whether the final answer contains the expected answer fragments
 *
 * Run: mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-tool \
 *      -Dtest=ToolCallEvalTest [-Deval.smoke=true]
 */
@Tag("eval-tool")
@SpringBootTest
@ActiveProfiles("local-gpu")
@TestPropertySource(properties = {
        "chat.routing.default-model=deepseek-chat",
        "chat.routing.deep-thinking-model=deepseek-chat",
        "chat.routing.candidates[0].id=deepseek-chat",
        "chat.routing.candidates[0].spring-client-key=deepseek-chat",
        "chat.routing.candidates[0].priority=1",
        "chat.routing.candidates[0].enabled=true",
        "chat.routing.candidates[0].supports-thinking=false",
        "chat.routing.candidates[0].thinking-strategy=NONE"
})
class ToolCallEvalTest {

    private static final int SMOKE_CASES_PER_CATEGORY = 1;

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

    @org.springframework.beans.factory.annotation.Autowired
    private LLMService llmService;

    @org.springframework.beans.factory.annotation.Autowired
    private PromptLoader promptLoader;

    @org.springframework.beans.factory.annotation.Autowired
    private ConversationTurnPreparationService turnPreparationService;

    private List<ToolGoldenScenario> scenarios;

    @BeforeEach
    void setUp() {
        List<ToolGoldenScenario> all = GoldenDatasetLoader.loadToolGolden();
        if (Boolean.getBoolean("eval.smoke")) {
            scenarios = all.stream()
                    .collect(Collectors.groupingBy(ToolGoldenScenario::category, LinkedHashMap::new, Collectors.toList()))
                    .values().stream()
                    .flatMap(list -> list.stream().limit(SMOKE_CASES_PER_CATEGORY))
                    .toList();
        } else {
            scenarios = all;
        }
    }

    @Test
    void evaluateToolCalling() throws Exception {
        List<ScenarioResult> results = new ArrayList<>();
        List<ToolCallback> toolCallbacks = buildToolCallbacks();

        for (int i = 0; i < scenarios.size(); i++) {
            ToolGoldenScenario scenario = scenarios.get(i);
            String sessionId = "tool-eval-" + scenario.id();
            String turnId = "turn-" + scenario.id();

            TurnPreparationResult preparation = turnPreparationService.prepare(
                    EvalTestTreeFactory.AGENT_ID,
                    sessionId,
                    scenario.query()
            );
            String actualIntentKind = preparation.intentResolution() == null
                    ? null
                    : preparation.intentResolution().kind().name();

            EvalMessageBridge bridge = new EvalMessageBridge(llmService);
            String systemPrompt = buildEvalSystemPrompt();
            ChatAgent agent = new ChatAgent(
                    EvalTestTreeFactory.AGENT_ID,
                    "Eval Assistant",
                    "Tool-call evaluation agent",
                    systemPrompt,
                    promptLoader,
                    llmService,
                    12,
                    List.of(new UserMessage(scenario.query())),
                    toolCallbacks,
                    "",
                    "",
                    "",
                    "eval-user",
                    turnId,
                    sessionId,
                    bridge
            );

            ScenarioResult result = new ScenarioResult();
            result.id = scenario.id();
            result.category = scenario.category();
            result.domain = scenario.domain();
            result.query = scenario.query();
            result.expectedIntentKind = scenario.expectedIntentKind();
            result.actualIntentKind = actualIntentKind;
            result.intentKindMatch = scenario.expectedIntentKind() != null
                    && scenario.expectedIntentKind().equals(actualIntentKind);
            result.preparedRewrittenInput = preparation.rewrittenInput();
            result.directReply = preparation.directReply();

            try {
                agent.run();
                result.actualTools = bridge.distinctToolCalls();
                result.actualStepCount = bridge.decisionSteps();
                result.finalAnswer = bridge.finalAnswer();
                result.error = null;
            } catch (Exception e) {
                result.actualTools = bridge.distinctToolCalls();
                result.actualStepCount = bridge.decisionSteps();
                result.finalAnswer = bridge.finalAnswer();
                result.error = e.getMessage();
            }

            result.expectedTools = scenario.expectedTools();
            result.expectedMaxSteps = scenario.expectedMaxSteps();
            result.expectedAnswerContains = scenario.expectedAnswerContains();
            result.toolPrecision = toolPrecision(result.actualTools, result.expectedTools);
            result.toolRecall = toolRecall(result.actualTools, result.expectedTools);
            result.toolF1 = f1(result.toolPrecision, result.toolRecall);
            result.toolSetExactMatch = new LinkedHashSet<>(result.actualTools)
                    .equals(new LinkedHashSet<>(result.expectedTools));
            result.withinMaxSteps = result.actualStepCount > 0 && result.actualStepCount <= scenario.expectedMaxSteps();
            result.answerContains = matchedFragments(result.finalAnswer, scenario.expectedAnswerContains());
            result.answerContainmentRate = ratio(result.answerContains.size(), scenario.expectedAnswerContains().size());
            result.answerContainsAll = result.answerContainmentRate >= 1.0;
            result.pass = result.intentKindMatch
                    && result.toolSetExactMatch
                    && result.withinMaxSteps
                    && result.answerContainsAll
                    && result.error == null;
            results.add(result);

            if ((i + 1) % 3 == 0) {
                Thread.sleep(1000);
            }
        }

        Map<String, Object> overall = aggregateOverall(results);
        Map<String, Map<String, Object>> byCategory = aggregateByCategory(results);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "tool-call-eval");
        report.put("mode", "real-llm-with-fake-tools");
        report.put("smokeMode", Boolean.getBoolean("eval.smoke"));
        report.put("totalScenarios", results.size());
        report.put("overall", overall);
        report.put("byCategory", byCategory);
        report.put("perScenario", results.stream().map(ScenarioResult::toMap).toList());

        Path reportPath = EvalReportWriter.writeReport("tool-call-eval", report);
        System.out.println("=== Tool Call Evaluation ===");
        System.out.println("Report: " + reportPath);
        System.out.println("Overall:\n" + new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(overall));

        assertThat(results).isNotEmpty();
        assertThat(overall.get("toolF1")).isNotNull();
        assertThat(overall.get("withinMaxStepsRate")).isNotNull();
        assertThat(overall.get("answerContainmentRate")).isNotNull();
    }

    private String buildEvalSystemPrompt() {
        return promptLoader.load(PromptConstants.AGENT_DEFAULT_SYSTEM) + "\n\n"
                + "You are running a tool-calling evaluation.\n"
                + "Rules:\n"
                + "1. Every user request in this test must use one or more relevant tools before answering.\n"
                + "2. If the request contains multiple actions, call multiple tools as needed.\n"
                + "3. Do not call irrelevant tools.\n"
                + "4. After tool results arrive, answer the user briefly in Chinese.\n"
                + "5. Mention what was done, but do not invent tool results.";
    }

    private List<ToolCallback> buildToolCallbacks() {
        return List.of(
                new EchoToolCallback("emailTool", "发送邮件、通知、邀请、群发、请假或出差申请邮件。", "邮件任务已处理"),
                new EchoToolCallback("meetingRoomTool", "查询空闲会议室、预订会议室、取消或更换会议室。", "会议室任务已处理"),
                new EchoToolCallback("vpnTool", "检查VPN状态、重置VPN密码、提交VPN开通或连接相关操作。", "VPN任务已处理"),
                new EchoToolCallback("permissionTool", "提交权限申请、修改读写权限、处理系统或文档库访问权限。", "权限任务已处理"),
                new EchoToolCallback("kbSearchTool", "检索公司制度知识库，如年假政策、报销材料、入职培训、密码策略等。", "知识库检索已处理")
        );
    }

    private static Map<String, Object> aggregateOverall(List<ScenarioResult> results) {
        double intentKindAccuracy = results.stream().filter(result -> result.intentKindMatch).count() / (double) results.size();
        double exactToolMatchRate = results.stream().filter(result -> result.toolSetExactMatch).count() / (double) results.size();
        double withinMaxStepsRate = results.stream().filter(result -> result.withinMaxSteps).count() / (double) results.size();
        double answerContainmentRate = results.stream().mapToDouble(result -> result.answerContainmentRate).average().orElse(0.0);
        double toolF1 = results.stream().mapToDouble(result -> result.toolF1).average().orElse(0.0);
        double passRate = results.stream().filter(result -> result.pass).count() / (double) results.size();

        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("intentKindAccuracy", round4(intentKindAccuracy));
        overall.put("exactToolMatchRate", round4(exactToolMatchRate));
        overall.put("toolF1", round4(toolF1));
        overall.put("withinMaxStepsRate", round4(withinMaxStepsRate));
        overall.put("answerContainmentRate", round4(answerContainmentRate));
        overall.put("passRate", round4(passRate));
        return overall;
    }

    private static Map<String, Map<String, Object>> aggregateByCategory(List<ScenarioResult> results) {
        Map<String, Map<String, Object>> byCategory = new LinkedHashMap<>();
        results.stream()
                .collect(Collectors.groupingBy(result -> result.category, LinkedHashMap::new, Collectors.toList()))
                .forEach((category, categoryResults) -> byCategory.put(category, aggregateOverall(categoryResults)));
        return byCategory;
    }

    private static double toolPrecision(List<String> actualTools, List<String> expectedTools) {
        if (actualTools == null || actualTools.isEmpty()) {
            return 0.0;
        }
        Set<String> actual = new LinkedHashSet<>(actualTools);
        Set<String> expected = new LinkedHashSet<>(expectedTools);
        actual.retainAll(expected);
        return ratio(actual.size(), new LinkedHashSet<>(actualTools).size());
    }

    private static double toolRecall(List<String> actualTools, List<String> expectedTools) {
        if (expectedTools == null || expectedTools.isEmpty()) {
            return 0.0;
        }
        Set<String> actual = new LinkedHashSet<>(actualTools);
        Set<String> expected = new LinkedHashSet<>(expectedTools);
        actual.retainAll(expected);
        return ratio(actual.size(), expected.size());
    }

    private static double f1(double precision, double recall) {
        if (precision <= 0.0 || recall <= 0.0) {
            return 0.0;
        }
        return 2 * precision * recall / (precision + recall);
    }

    private static List<String> matchedFragments(String answer, List<String> expectedFragments) {
        if (expectedFragments == null || expectedFragments.isEmpty()) {
            return List.of();
        }
        List<String> matched = new ArrayList<>();
        for (String fragment : expectedFragments) {
            if (containsLoose(answer, fragment)) {
                matched.add(fragment);
            }
        }
        return matched;
    }

    private static boolean containsLoose(String text, String expected) {
        if (text == null || expected == null) {
            return false;
        }
        String normalizedText = normalize(text);
        String normalizedExpected = normalize(expected);
        return !normalizedExpected.isBlank() && normalizedText.contains(normalizedExpected);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim()
                .toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("\n", "")
                .replace("\r", "")
                .replace(",", "")
                .replace("，", "")
                .replace("。", "")
                .replace("：", "")
                .replace("；", "");
    }

    private static double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0.0 : (double) numerator / denominator;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static final class EchoToolCallback implements ToolCallback {

        private final ToolDefinition definition;
        private final String responsePrefix;

        private EchoToolCallback(String name, String description, String responsePrefix) {
            this.definition = ToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema("""
                            {
                              "type": "object",
                              "properties": {
                                "query": {
                                  "type": "string",
                                  "description": "The user request to execute with this tool."
                                }
                              },
                              "required": ["query"]
                            }
                            """)
                    .build();
            this.responsePrefix = responsePrefix;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return responsePrefix + "；请求参数=" + (toolInput == null ? "{}" : toolInput);
        }
    }

    private static final class EvalMessageBridge implements AgentMessageBridge {

        private final LLMService llmService;
        private final List<String> toolCalls = new ArrayList<>();
        private int decisionSteps;
        private String finalAnswer = "";

        private EvalMessageBridge(LLMService llmService) {
            this.llmService = llmService;
        }

        @Override
        public void persistAndPublish(String chatSessionId, String turnId, Message message) {
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    toolCalls.add(response.name());
                }
                return;
            }
            if (message instanceof AssistantMessage assistantMessage
                    && (assistantMessage.getToolCalls() == null || assistantMessage.getToolCalls().isEmpty())) {
                finalAnswer = assistantMessage.getText() == null ? "" : assistantMessage.getText();
            }
        }

        @Override
        public String streamFinalResponse(String chatSessionId, String turnId, Prompt prompt, LLMService ignored, boolean deepThinking) {
            decisionSteps++;
            ChatResponse response = streamToResponse(prompt, null, List.of());
            finalAnswer = extractText(response);
            return finalAnswer;
        }

        @Override
        public BufferedStreamingResponse streamDecisionResponse(String chatSessionId,
                                                               String turnId,
                                                               Prompt prompt,
                                                               String systemPrompt,
                                                               List<ToolCallback> tools,
                                                               LLMService ignored) {
            decisionSteps++;
            BufferedStreamingResponse bufferedResponse = llmService.streamDecisionWithRouting(prompt, systemPrompt, tools);
            ChatResponse response = bufferedResponse.response();
            if (response != null && !response.hasToolCalls()) {
                finalAnswer = extractText(response);
            }
            return bufferedResponse;
        }

        @Override
        public BufferedStreamingResponse collectDecisionResponse(String chatSessionId, String turnId, Prompt prompt, String systemPrompt, List<ToolCallback> tools, LLMService llmService, DecisionVisibility visibility, boolean deepThinking, String deepThinkPhase, String planStepId) {
            // Eval bridge does not use DeepThink; delegate to user-visible path.
            return streamDecisionResponse(chatSessionId, turnId, prompt, systemPrompt, tools, llmService);
        }

        @Override
        public void publishStatusEvent(String chatSessionId, String turnId, com.yulong.chatagent.conversation.model.SseMessage.Type type, String statusText) {
            // No-op for eval
        }

        @Override
        public void persistInternalToolResponses(String chatSessionId, String turnId, ToolResponseMessage toolResponseMessage, String deepThinkPhase, String planStepId) {
            // Eval bridge does not use DeepThink internal tool responses.
        }

        @Override
        public void attachTraceMetadata(String chatSessionId, String turnId, com.yulong.chatagent.support.dto.AgentTraceMetadata trace) {
            // No-op for eval
        }

        List<String> distinctToolCalls() {
            return List.copyOf(new LinkedHashSet<>(toolCalls));
        }

        int decisionSteps() {
            return decisionSteps;
        }

        String finalAnswer() {
            return finalAnswer == null ? "" : finalAnswer;
        }

        private ChatResponse streamToResponse(Prompt prompt, String systemPrompt, List<ToolCallback> tools) {
            BufferedStreamingResponse bufferedResponse = llmService.streamDecisionWithRouting(prompt, systemPrompt, tools);
            return bufferedResponse == null ? null : bufferedResponse.response();
        }

        private static String extractText(ChatResponse response) {
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return "";
            }
            String text = response.getResult().getOutput().getText();
            return text == null ? "" : text;
        }
    }

    private static final class ScenarioResult {
        String id;
        String category;
        String domain;
        String query;
        String expectedIntentKind;
        String actualIntentKind;
        boolean intentKindMatch;
        List<String> expectedTools = List.of();
        List<String> actualTools = List.of();
        int expectedMaxSteps;
        int actualStepCount;
        boolean withinMaxSteps;
        List<String> expectedAnswerContains = List.of();
        List<String> answerContains = List.of();
        double answerContainmentRate;
        boolean answerContainsAll;
        double toolPrecision;
        double toolRecall;
        double toolF1;
        boolean toolSetExactMatch;
        String preparedRewrittenInput;
        String directReply;
        String finalAnswer;
        String error;
        boolean pass;

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("category", category);
            map.put("domain", domain);
            map.put("query", query);
            map.put("expectedIntentKind", expectedIntentKind);
            map.put("actualIntentKind", actualIntentKind);
            map.put("intentKindMatch", intentKindMatch);
            map.put("expectedTools", expectedTools);
            map.put("actualTools", actualTools);
            map.put("toolPrecision", round4(toolPrecision));
            map.put("toolRecall", round4(toolRecall));
            map.put("toolF1", round4(toolF1));
            map.put("toolSetExactMatch", toolSetExactMatch);
            map.put("expectedMaxSteps", expectedMaxSteps);
            map.put("actualStepCount", actualStepCount);
            map.put("withinMaxSteps", withinMaxSteps);
            map.put("expectedAnswerContains", expectedAnswerContains);
            map.put("answerContains", answerContains);
            map.put("answerContainmentRate", round4(answerContainmentRate));
            map.put("answerContainsAll", answerContainsAll);
            map.put("preparedRewrittenInput", preparedRewrittenInput);
            map.put("directReply", directReply);
            map.put("finalAnswer", finalAnswer);
            map.put("error", error);
            map.put("pass", pass);
            return map;
        }
    }
}
