package com.yulong.chatagent.intent.application;

/**
 * Normalized output of turn preparation before dispatch.
 */
public record TurnPreparationResult(
        IntentResolution intentResolution,
        String rewrittenInput,
        String directReply
) {
    public boolean isDirectReply() {
        return directReply != null && !directReply.isBlank();
    }

    public static TurnPreparationResult dispatch(IntentResolution resolution, String rewrittenInput) {
        return new TurnPreparationResult(resolution, rewrittenInput, null);
    }

    public static TurnPreparationResult direct(String directReply) {
        return new TurnPreparationResult(null, null, directReply);
    }

    public static TurnPreparationResult passthrough() {
        return new TurnPreparationResult(null, null, null);
    }
}

