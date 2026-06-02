package com.yulong.chatagent.agent.deepthink;

import com.yulong.chatagent.support.dto.AgentTraceMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DeepThinkTraceSanitizer} — boundary conditions, truncation, status mapping.
 */
class DeepThinkTraceSanitizerTest {

    @Test
    void truncatesLongGoal() {
        String longGoal = "A".repeat(300);
        DeepThinkPlan plan = planWithGoal(longGoal);
        AgentTraceMetadata trace = DeepThinkTraceSanitizer.buildTrace("DEEPTHINK", plan, new DeepThinkNotebook());

        assertThat(trace.getPlanning().getGoal()).hasSize(200);
        assertThat(trace.getPlanning().getGoal()).endsWith("…");
    }

    @Test
    void truncatesLongTitle() {
        DeepThinkPlanStep step = DeepThinkPlanStep.builder()
                .id("S1").title("B".repeat(150)).objective("obj")
                .status("COMPLETED").build();
        DeepThinkPlan plan = DeepThinkPlan.builder().goal("g").steps(List.of(step)).build();

        AgentTraceMetadata trace = DeepThinkTraceSanitizer.buildTrace("DEEPTHINK", plan, new DeepThinkNotebook());
        assertThat(trace.getPlanning().getSteps().get(0).getTitle()).hasSize(100);
        assertThat(trace.getPlanning().getSteps().get(0).getTitle()).endsWith("…");
    }

    @Test
    void truncatesLongConclusion() {
        DeepThinkNotebook notebook = new DeepThinkNotebook();
        DeepThinkPlanStep step = DeepThinkPlanStep.builder()
                .id("S1").title("t").objective("o").build();
        notebook.recordStepCompletion(step, "C".repeat(300));

        AgentTraceMetadata trace = DeepThinkTraceSanitizer.buildTrace("DEEPTHINK",
                planWithGoal("g"), notebook);
        assertThat(trace.getExecution().getStepSummaries().get(0).getConclusion()).hasSize(200);
        assertThat(trace.getExecution().getStepSummaries().get(0).getConclusion()).endsWith("…");
    }

    @Test
    void mapsReflectionStatus_revisePlan_toRevised() {
        DeepThinkReflectionResult result = DeepThinkReflectionResult.builder()
                .status("REVISE_PLAN")
                .rounds(1)
                .covered(List.of("S1"))
                .missing(List.of())
                .contradictions(List.of())
                .revisedSteps(List.of(DeepThinkPlanStep.builder().id("R1").title("rev").objective("rev").build()))
                .build();

        AgentTraceMetadata trace = DeepThinkTraceSanitizer.buildTrace("DEEPTHINK",
                planWithGoal("g"), new DeepThinkNotebook(), result, null);

        assertThat(trace.getReflection().getStatus()).isEqualTo("REVISED");
    }

    @Test
    void mapsReflectionStatus_readyToVerify_toContinue() {
        DeepThinkReflectionResult result = DeepThinkReflectionResult.builder()
                .status("READY_TO_VERIFY")
                .rounds(1)
                .covered(List.of("S1"))
                .missing(List.of())
                .contradictions(List.of())
                .revisedSteps(List.of())
                .build();

        AgentTraceMetadata trace = DeepThinkTraceSanitizer.buildTrace("DEEPTHINK",
                planWithGoal("g"), new DeepThinkNotebook(), result, null);

        assertThat(trace.getReflection().getStatus()).isEqualTo("CONTINUE");
    }

    @Test
    void mapsReflectionStatus_needUserClarification_toContinue() {
        DeepThinkReflectionResult result = DeepThinkReflectionResult.builder()
                .status("NEED_USER_CLARIFICATION")
                .rounds(1)
                .covered(List.of())
                .missing(List.of())
                .contradictions(List.of())
                .reasonForUserClarification("需要澄清")
                .revisedSteps(List.of())
                .build();

        AgentTraceMetadata trace = DeepThinkTraceSanitizer.buildTrace("DEEPTHINK",
                planWithGoal("g"), new DeepThinkNotebook(), result, null);

        assertThat(trace.getReflection().getStatus()).isEqualTo("CONTINUE");
    }

    @Test
    void mapsReflectionStatus_skipped() {
        DeepThinkReflectionResult result = DeepThinkReflectionResult.skipped("budget exhausted");

        AgentTraceMetadata trace = DeepThinkTraceSanitizer.buildTrace("DEEPTHINK",
                planWithGoal("g"), new DeepThinkNotebook(), result, null);

        assertThat(trace.getReflection().getStatus()).isEqualTo("SKIPPED");
        assertThat(trace.getReflection().getRounds()).isEqualTo(0);
    }

    @Test
    void truncatesVerificationIssueClaim() {
        DeepThinkVerificationResult result = DeepThinkVerificationResult.builder()
                .passed(false)
                .issues(List.of(
                        DeepThinkVerificationResult.Issue.builder()
                                .type("UNSUPPORTED_CLAIM")
                                .claim("X".repeat(200))
                                .fix("fix it")
                                .build()))
                .build();

        AgentTraceMetadata trace = DeepThinkTraceSanitizer.buildTrace("DEEPTHINK",
                planWithGoal("g"), new DeepThinkNotebook(), null, result);

        assertThat(trace.getVerification().getIssues().get(0).getClaim()).hasSize(100);
        assertThat(trace.getVerification().getIssues().get(0).getClaim()).endsWith("…");
    }

    @Test
    void traceContainsNoRawToolPayloads() {
        DeepThinkNotebook notebook = new DeepThinkNotebook();
        DeepThinkPlanStep step = DeepThinkPlanStep.builder()
                .id("S1").title("t").objective("o").build();
        notebook.recordStepCompletion(step, "conclusion text");
        notebook.recordToolUsage("knowledgeQuery", 3);

        AgentTraceMetadata trace = DeepThinkTraceSanitizer.buildTrace("DEEPTHINK",
                planWithGoal("g"), notebook);

        // Execution trace should only contain tool names, not arguments or responses
        assertThat(trace.getExecution().getToolsUsed()).containsExactly("knowledgeQuery");
        assertThat(trace.getExecution().getTotalToolCalls()).isEqualTo(3);
        // No raw response content anywhere in the trace
        String serialized = trace.toString();
        assertThat(serialized).doesNotContain("arguments");
        assertThat(serialized).doesNotContain("responseData");
    }

    @Test
    void nullReflectionAndVerification_producesNullSubObjects() {
        AgentTraceMetadata trace = DeepThinkTraceSanitizer.buildTrace("DEEPTHINK",
                planWithGoal("g"), new DeepThinkNotebook());

        assertThat(trace.getReflection()).isNull();
        assertThat(trace.getVerification()).isNull();
        assertThat(trace.getPlanning()).isNotNull();
        assertThat(trace.getExecution()).isNotNull();
    }

    private DeepThinkPlan planWithGoal(String goal) {
        return DeepThinkPlan.builder().goal(goal).steps(List.of()).build();
    }
}
