package com.yulong.chatagent.agent.deepthink;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeepThinkNotebookTest {

    @Test
    void evidenceIsBoundedAndRetainsSourceIdentity() {
        DeepThinkNotebook notebook = new DeepThinkNotebook();
        notebook.recordEvidence("S1", "webSearch", "call-1", "x".repeat(2_100));
        for (int i = 2; i <= 50; i++) {
            notebook.recordEvidence("S1", "webSearch", "call-" + i, "ok");
        }

        assertThat(notebook.getEvidence()).hasSize(DeepThinkNotebook.MAX_EVIDENCE_ENTRIES);
        DeepThinkEvidenceEntry first = notebook.getEvidence().get(0);
        assertThat(first.stepId()).isEqualTo("S1");
        assertThat(first.toolName()).isEqualTo("webSearch");
        assertThat(first.toolCallId()).isEqualTo("call-1");
        assertThat(first.truncated()).isTrue();
        assertThat(first.content()).hasSize(DeepThinkNotebook.MAX_EVIDENCE_CHARS);
    }

    @Test
    void partialTextCannotBeMisclassifiedAsCompleted() {
        DeepThinkNotebook notebook = new DeepThinkNotebook();
        DeepThinkPlanStep step = DeepThinkPlanStep.builder().id("S1").title("step").build();
        notebook.recordStepResult(step,
                DeepThinkStepResult.partial("Partially completed", "NO_CONCLUSION"));

        assertThat(notebook.hasIncompleteSteps()).isTrue();
        assertThat(notebook.getCompletedSteps().get(0).getStatus()).isEqualTo("PARTIAL");
    }
}
