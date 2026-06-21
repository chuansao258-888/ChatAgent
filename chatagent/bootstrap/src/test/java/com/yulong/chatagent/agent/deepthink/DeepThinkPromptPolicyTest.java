package com.yulong.chatagent.agent.deepthink;

import com.yulong.chatagent.TestPromptLoader;
import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeepThinkPromptPolicyTest {

    private final PromptLoader promptLoader = TestPromptLoader.create();

    @Test
    void plannerShouldKeepNoToolGeneralKnowledgeRequestsAwayFromContextRetrieval() {
        String prompt = promptLoader.render(PromptConstants.DEEPTHINK_PLANNER, Map.of(
                "availableTools", "SessionFileSearchTool, webSearchTool",
                "maxPlanItems", "2",
                "userQuestion", "Use general knowledge only. Include DEEPTHINK-E2E-123. Do not use tools.",
                "sessionContext", "Knowledge base: E2E RAG KB 1781710991"
        ));

        assertThat(prompt)
                .contains("Session context is background evidence only")
                .contains("It is not the task")
                .contains("Do not infer")
                .contains("plan a direct no-tool answer")
                .contains("set `suggestedTools` to []")
                .contains("Preserve explicit output constraints")
                .contains("fixed markers")
                .contains("do not use tools/documents");
    }

    @Test
    void stepExecutorShouldHonorNoToolExactMarkerObjectives() {
        String prompt = promptLoader.render(PromptConstants.DEEPTHINK_STEP_EXECUTOR, Map.of(
                "stepId", "S1",
                "stepTitle", "Answer directly",
                "stepObjective", "Use general knowledge only and include DEEPTHINK-E2E-123",
                "stepDoneCriteria", "Do not use external documents or tools",
                "planGoal", "Answer without tools",
                "stepExpectedEvidence", "Direct answer",
                "observations", "none",
                "availableTools", "SessionFileSearchTool"
        ));

        assertThat(prompt)
                .contains("avoid external documents")
                .contains("avoid tools")
                .contains("preserve an exact marker")
                .contains("do not call tools");
    }

    @Test
    void finalSynthesisShouldPreserveLatestUserOutputConstraintsOverContextAvailability() {
        String prompt = promptLoader.render(PromptConstants.DEEPTHINK_FINAL_SYNTHESIS, Map.of(
                "sessionFileSummary", "No active chat session",
                "relevantLongTermMemories", "Knowledge base unavailable",
                "goal", "Use general knowledge only and include DEEPTHINK-E2E-123",
                "observations", "Benefit: visible debugging; limitation: slower than headless.",
                "reflectionSummary", "READY_TO_VERIFY",
                "verificationSummary", "passed",
                "caveats", "none"
        ));

        assertThat(prompt)
                .contains("background evidence")
                .contains("not the task")
                .contains("Preserve explicit output constraints")
                .contains("fixed markers")
                .contains("do not use external documents/tools")
                .contains("include it")
                .contains("do not replace the answer with a knowledge-base, session-file")
                .contains("memory availability message");
    }
}
