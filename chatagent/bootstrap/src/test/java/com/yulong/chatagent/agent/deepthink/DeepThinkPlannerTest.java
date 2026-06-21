package com.yulong.chatagent.agent.deepthink;

import com.yulong.chatagent.agent.AgentMessageBridge;
import com.yulong.chatagent.agent.DecisionVisibility;
import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.conversation.model.SseMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DeepThinkJsonParser} — plan JSON parsing and extraction.
 */
class DeepThinkPlannerTest {

    @Test
    void plan_englishQuestionUsesEnglishStatusAndInstruction() {
        AgentMessageBridge messageBridge = mock(AgentMessageBridge.class);
        LLMService llmService = mock(LLMService.class);
        PromptLoader promptLoader = mock(PromptLoader.class);
        when(promptLoader.render(eq(PromptConstants.DEEPTHINK_PLANNER), any()))
                .thenReturn("rendered planner prompt");
        AssistantMessage assistant = AssistantMessage.builder()
                .content("""
                        {"goal":"Diagnose browser test flakiness","complexity":"LOW","steps":[{"id":"S1","title":"Check session state","objective":"Compare storage state"}],"risks":[]}
                        """)
                .build();
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService), eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(false), eq("PLAN"), eq(null)
        )).thenReturn(new BufferedStreamingResponse(
                new ChatResponse(java.util.List.of(new Generation(assistant))), java.util.List.of()));
        DeepThinkPlanner planner = new DeepThinkPlanner(messageBridge, llmService, java.util.List.of(), false, promptLoader);

        DeepThinkPlan plan = planner.plan(
                "session-1", "turn-1",
                "Diagnose why a browser test fails after login.",
                "", 3);

        assertThat(plan).isNotNull();
        verify(messageBridge).publishStatusEvent(
                eq("session-1"), eq("turn-1"), eq(SseMessage.Type.AI_PLANNING), eq("Planning..."));
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(messageBridge).collectDecisionResponse(
                eq("session-1"), eq("turn-1"), promptCaptor.capture(), anyString(),
                anyList(), eq(llmService), eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(false), eq("PLAN"), eq(null));
        assertThat(promptCaptor.getValue().getInstructions())
                .anySatisfy(message -> {
                    assertThat(message).isInstanceOf(UserMessage.class);
                    assertThat(message.getText()).isEqualTo("Generate the execution plan from the information above.");
                });
    }

    @Test
    void parsePlan_validJson_returnsPlan() {
        String json = """
                {
                  "goal": "查找最新的 Spring AI 版本信息",
                  "complexity": "MEDIUM",
                  "assumptions": ["用户需要最新版本号"],
                  "steps": [
                    {
                      "id": "S1",
                      "title": "搜索 Spring AI 最新版本",
                      "objective": "通过 web search 查找 Spring AI 最新发布版本",
                      "expectedEvidence": ["版本号", "发布日期"],
                      "suggestedTools": ["webSearchTool"],
                      "doneCriteria": ["获得版本号"]
                    },
                    {
                      "id": "S2",
                      "title": "查找变更日志",
                      "objective": "查看最新版本的变更内容",
                      "expectedEvidence": ["主要变更"],
                      "suggestedTools": ["webSearchTool"],
                      "doneCriteria": ["获得变更摘要"]
                    }
                  ],
                  "risks": ["搜索结果可能包含过时信息"]
                }
                """;

        DeepThinkPlan plan = DeepThinkJsonParser.parsePlan(json, 5);

        assertThat(plan).isNotNull();
        assertThat(plan.getGoal()).isEqualTo("查找最新的 Spring AI 版本信息");
        assertThat(plan.getComplexity()).isEqualTo("MEDIUM");
        assertThat(plan.getAssumptions()).containsExactly("用户需要最新版本号");
        assertThat(plan.getSteps()).hasSize(2);
        assertThat(plan.getSteps().get(0).getId()).isEqualTo("S1");
        assertThat(plan.getSteps().get(0).getTitle()).isEqualTo("搜索 Spring AI 最新版本");
        assertThat(plan.getSteps().get(0).getObjective()).contains("web search");
        assertThat(plan.getSteps().get(1).getId()).isEqualTo("S2");
        assertThat(plan.getRisks()).hasSize(1);
    }

    @Test
    void parsePlan_withMarkdownFence_extractsJson() {
        String output = """
                根据用户的问题，我制定了以下执行计划：

                ```json
                {
                  "goal": "测试目标",
                  "complexity": "LOW",
                  "assumptions": [],
                  "steps": [
                    {
                      "id": "S1",
                      "title": "第一步",
                      "objective": "做一件事",
                      "expectedEvidence": ["证据1"],
                      "suggestedTools": [],
                      "doneCriteria": ["完成标准1"]
                    }
                  ],
                  "risks": []
                }
                ```

                以上是计划内容。
                """;

        DeepThinkPlan plan = DeepThinkJsonParser.parsePlan(output, 5);

        assertThat(plan).isNotNull();
        assertThat(plan.getGoal()).isEqualTo("测试目标");
        assertThat(plan.getSteps()).hasSize(1);
    }

    @Test
    void parsePlan_invalidJson_returnsNull() {
        assertThat(DeepThinkJsonParser.parsePlan("this is not json", 5)).isNull();
        assertThat(DeepThinkJsonParser.parsePlan("", 5)).isNull();
        assertThat(DeepThinkJsonParser.parsePlan(null, 5)).isNull();
        assertThat(DeepThinkJsonParser.parsePlan("   ", 5)).isNull();
    }

    @Test
    void parsePlan_tooManySteps_clampsToMax() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"goal\":\"g\",\"complexity\":\"HIGH\",\"steps\":[");
        for (int i = 1; i <= 10; i++) {
            if (i > 1) sb.append(",");
            sb.append("{\"id\":\"S").append(i).append("\",\"title\":\"t").append(i)
                    .append("\",\"objective\":\"o").append(i).append("\"}");
        }
        sb.append("]}");

        DeepThinkPlan plan = DeepThinkJsonParser.parsePlan(sb.toString(), 5);

        assertThat(plan).isNotNull();
        assertThat(plan.getSteps()).hasSize(5); // clamped from 10 to 5
        assertThat(plan.getSteps().get(4).getId()).isEqualTo("S5");
    }

    @Test
    void parsePlan_missingGoal_returnsNull() {
        String json = """
                {"complexity": "LOW", "steps": []}
                """;

        assertThat(DeepThinkJsonParser.parsePlan(json, 5)).isNull();
    }

    @Test
    void extractJson_handlesBareJson() {
        String json = "{\"goal\":\"test\",\"steps\":[]}";
        assertThat(DeepThinkJsonParser.extractJson(json)).isEqualTo(json);
    }

    @Test
    void extractJson_handlesCodeFenceWithoutLanguage() {
        String output = "```\n{\"goal\":\"test\",\"steps\":[]}\n```";
        assertThat(DeepThinkJsonParser.extractJson(output)).isEqualTo("{\"goal\":\"test\",\"steps\":[]}");
    }

    @Test
    void parseReflectionResult_validJson_returnsResult() {
        String json = """
                {
                  "status": "REVISE_PLAN",
                  "covered": ["S1"],
                  "missing": ["缺少来源"],
                  "contradictions": [],
                  "revisedSteps": [
                    {"id":"R1","title":"补充来源","objective":"查找来源"}
                  ]
                }
                """;

        DeepThinkReflectionResult result = DeepThinkJsonParser.parseReflectionResult(json);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(DeepThinkReflectionResult.REVISE_PLAN);
        assertThat(result.getCovered()).containsExactly("S1");
        assertThat(result.getMissing()).containsExactly("缺少来源");
        assertThat(result.getRevisedSteps()).hasSize(1);
        assertThat(result.getRevisedSteps().get(0).getId()).isEqualTo("R1");
    }

    @Test
    void parseVerificationResult_validJson_returnsResult() {
        String json = """
                {
                  "passed": false,
                  "issues": [{"type":"MISSING_SOURCE","claim":"缺少来源","fix":"补充说明"}],
                  "requiredFollowUpActions": [
                    {"id":"V1","title":"验证来源","objective":"补充来源"}
                  ],
                  "caveat": "未经完整验证"
                }
                """;

        DeepThinkVerificationResult result = DeepThinkJsonParser.parseVerificationResult(json);

        assertThat(result).isNotNull();
        assertThat(result.isPassed()).isFalse();
        assertThat(result.getIssues()).hasSize(1);
        assertThat(result.getIssues().get(0).getType()).isEqualTo("MISSING_SOURCE");
        assertThat(result.getRequiredFollowUpActions()).hasSize(1);
        assertThat(result.getCaveat()).isEqualTo("未经完整验证");
    }
}
