package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.contract.ActionRisk;
import com.yulong.chatagent.agent.runtime.contract.IntentLabel;
import com.yulong.chatagent.agent.runtime.contract.SourceNeed;
import com.yulong.chatagent.agent.runtime.contract.TimeSensitivity;

import java.util.List;

/** Complete typed understanding result consumed by turn preparation and the contract builder. */
public record IntentUnderstandingResult(
        IntentDecision decision,
        SourceNeed sourceNeed,
        TimeSensitivity timeSensitivity,
        ActionRisk actionRisk,
        List<IntentLabel> secondaryIntents,
        boolean contextUsed,
        boolean contextTruncated
) {
    public IntentUnderstandingResult {
        secondaryIntents = secondaryIntents == null ? List.of() : List.copyOf(secondaryIntents);
    }
}
