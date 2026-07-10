package com.yulong.chatagent.agent.runtime.contract;

import java.util.List;

/**
 * Structured understanding result for the latest user turn.
 *
 * <p>It is produced by {@code ConversationTurnPreparationService} before the
 * execution contract is built. Phase 1 derives it conservatively from the
 * resolved intent (or general chat when there is no intent); later phases add
 * LLM-assisted classification and preservation checks.</p>
 *
 * @param originalUserText  the raw user input, preserved for auditing and preservation checks
 * @param primaryIntent     primary intent label for compatibility with the intent tree
 * @param secondaryIntents  additional intents such as COMPARE or CURRENTNESS
 * @param sourceNeed        which retrieval source the turn needs
 * @param toolNeed          whether tool execution is needed
 * @param timeSensitivity   how current the answer must be
 * @param actionRisk        risk profile of the requested action
 * @param ambiguity         blocking-ambiguity plan
 * @param confidence        overall understanding confidence in [0,1]
 */
public record TurnAnalysis(
        String originalUserText,
        IntentLabel primaryIntent,
        List<IntentLabel> secondaryIntents,
        SourceNeed sourceNeed,
        ToolNeed toolNeed,
        TimeSensitivity timeSensitivity,
        ActionRisk actionRisk,
        AmbiguityPlan ambiguity,
        double confidence
) {
    public TurnAnalysis {
        secondaryIntents = secondaryIntents == null ? List.of() : List.copyOf(secondaryIntents);
        ambiguity = ambiguity == null ? AmbiguityPlan.none() : ambiguity;
        if (confidence < 0.0d) {
            confidence = 0.0d;
        } else if (confidence > 1.0d) {
            confidence = 1.0d;
        }
    }
}
