package com.yulong.chatagent.agent.deepthink;

/** Typed step result; display text is never used to infer completion. */
public record DeepThinkStepResult(DeepThinkStepStatus status, String conclusion, String reasonCode) {
    public static DeepThinkStepResult completed(String conclusion) {
        return new DeepThinkStepResult(DeepThinkStepStatus.COMPLETED, conclusion, null);
    }

    public static DeepThinkStepResult partial(String conclusion, String reasonCode) {
        return new DeepThinkStepResult(DeepThinkStepStatus.PARTIAL, conclusion, reasonCode);
    }
}
