package com.yulong.chatagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Golden dialogue for memory/summary quality evaluation.
 * Maps to eval/golden/memory-golden.json.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemoryGoldenDialogue(
        String id,
        String domain,
        List<Turn> turns,
        List<String> expectedEntities,   // e.g. ["2026-03-15", "ORD-12345", "¥3,500"]
        List<String> expectedTopics      // e.g. ["年假申请", "报销流程"]
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Turn(
            String role,               // user | assistant
            String content,
            List<String> expectedSummaryMentions  // optional: what the summary should mention after this turn
    ) {}
}
