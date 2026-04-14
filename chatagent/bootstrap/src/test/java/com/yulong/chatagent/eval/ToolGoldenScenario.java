package com.yulong.chatagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Golden scenario for tool calling evaluation.
 * Maps to eval/golden/tool-golden.json.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolGoldenScenario(
        String id,
        String domain,
        String query,
        List<String> expectedTools,        // e.g. ["DataBaseTools.executeReadOnlyQuery"]
        int expectedMaxSteps,
        List<String> expectedAnswerContains,
        String category,                   // kb-search | sql-query | email | mcp | multi-tool
        String expectedIntentKind          // KB | TOOL
) {}
