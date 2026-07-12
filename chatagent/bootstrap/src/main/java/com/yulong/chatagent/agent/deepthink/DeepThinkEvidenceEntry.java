package com.yulong.chatagent.agent.deepthink;

/** Bounded evidence with source identity retained for verification and final synthesis. */
public record DeepThinkEvidenceEntry(
        String stepId,
        String toolName,
        String toolCallId,
        String content,
        boolean truncated) {
}
