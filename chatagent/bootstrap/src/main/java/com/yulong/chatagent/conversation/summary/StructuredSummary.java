package com.yulong.chatagent.conversation.summary;

import java.util.List;
import java.util.Map;

/**
 * Parsed result of a structured segment summary.
 *
 * <p>Replaces the free-form text summary with deterministic fields:
 * summary text, extracted facts, decisions, open tasks, and anchored entities.
 */
public record StructuredSummary(
        String summary,
        List<String> facts,
        List<String> decisions,
        List<String> openTasks,
        Map<String, List<String>> entities
) {

    private static final StructuredSummary EMPTY = new StructuredSummary(
            "", List.of(), List.of(), List.of(), Map.of());

    /**
     * Returns an empty structured summary used as a safe fallback.
     */
    public static StructuredSummary empty() {
        return EMPTY;
    }
}
