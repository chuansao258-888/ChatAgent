package com.yulong.chatagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Golden dataset entry for intent routing evaluation.
 * Maps to eval/golden/intent-golden.json.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IntentGoldenEntry(
        String id,
        String category,          // direct | ambiguous | cross-domain | out-of-scope | clarification-needed | system-intent
        String domain,            // hr | finance | it | admin
        String query,
        String expectedNodeId,
        String expectedKind,      // KB | TOOL | SYSTEM | CLARIFY | NONE
        List<String> expectedScopedKbIds,
        String expectedPathLabel, // e.g. "人事制度 > 考勤管理 > 年假政策"
        boolean expectedClarification,
        boolean expectedOutOfScope,
        boolean heuristicShouldSuffice
) {}
