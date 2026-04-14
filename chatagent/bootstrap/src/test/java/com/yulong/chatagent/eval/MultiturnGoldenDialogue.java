package com.yulong.chatagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Golden dialogue for multi-turn conversation quality evaluation.
 * Maps to eval/golden/multiturn-golden.json.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MultiturnGoldenDialogue(
        String id,
        String domain,
        List<Turn> turns,
        int totalTurns
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Turn(
            String role,                  // user | assistant
            String content,
            String expectedIntentPath,    // e.g. "人事制度 > 请假流程" (only for user turns)
            String expectedCoreference,   // optional: what "it"/"that" should resolve to
            boolean isTopicSwitch         // true if this turn switches the conversation topic
    ) {}
}
