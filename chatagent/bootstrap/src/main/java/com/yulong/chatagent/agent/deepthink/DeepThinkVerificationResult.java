package com.yulong.chatagent.agent.deepthink;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Sanitized result from the DeepThink verification stage.
 */
@Data
@Builder
public class DeepThinkVerificationResult {

    private boolean passed;
    private List<Issue> issues;
    private List<DeepThinkPlanStep> requiredFollowUpActions;
    private boolean skipped;
    private String caveat;
    private int rounds;

    public static DeepThinkVerificationResult passed() {
        return DeepThinkVerificationResult.builder()
                .passed(true)
                .issues(List.of())
                .requiredFollowUpActions(List.of())
                .build();
    }

    public static DeepThinkVerificationResult skipped(String reason) {
        return DeepThinkVerificationResult.builder()
                .passed(false)
                .issues(List.of())
                .requiredFollowUpActions(List.of())
                .skipped(true)
                .caveat(reason == null || reason.isBlank() ? "未经完整验证" : reason)
                .build();
    }

    public boolean hasFollowUpActions() {
        return requiredFollowUpActions != null && !requiredFollowUpActions.isEmpty();
    }

    @Data
    @Builder
    public static class Issue {
        private String type;
        private String claim;
        private String fix;
    }
}
