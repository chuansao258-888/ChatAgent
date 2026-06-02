package com.yulong.chatagent.agent.deepthink;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Sanitized result from the DeepThink reflection stage.
 */
@Data
@Builder
public class DeepThinkReflectionResult {

    public static final String CONTINUE = "CONTINUE";
    public static final String REVISE_PLAN = "REVISE_PLAN";
    public static final String READY_TO_VERIFY = "READY_TO_VERIFY";
    public static final String NEED_USER_CLARIFICATION = "NEED_USER_CLARIFICATION";
    public static final String SKIPPED = "SKIPPED";

    private String status;
    private List<String> covered;
    private List<String> missing;
    private List<String> contradictions;
    private List<DeepThinkPlanStep> revisedSteps;
    private String reasonForUserClarification;
    private int rounds;
    private boolean skipped;

    public static DeepThinkReflectionResult skipped(String reason) {
        return DeepThinkReflectionResult.builder()
                .status(SKIPPED)
                .missing(reason == null || reason.isBlank() ? List.of() : List.of(reason))
                .rounds(0)
                .skipped(true)
                .build();
    }

    public boolean needsUserClarification() {
        return NEED_USER_CLARIFICATION.equals(status);
    }

    public boolean requestsRevision() {
        return REVISE_PLAN.equals(status) && revisedSteps != null && !revisedSteps.isEmpty();
    }
}
