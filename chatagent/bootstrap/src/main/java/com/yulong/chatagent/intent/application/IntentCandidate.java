package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.support.dto.IntentNodeDTO;

import java.util.List;

/** Internal hierarchy-aware candidate used before safe evidence is frozen into a decision. */
public record IntentCandidate(
        IntentNodeDTO node,
        String path,
        double lexicalScore,
        double lexicalGap,
        boolean exactNameMatch,
        boolean exactExampleMatch,
        int stableOrder,
        List<String> reasonCodes
) {
    public IntentCandidate {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }

    public IntentCandidate withGap(double gap) {
        return new IntentCandidate(node, path, lexicalScore, Math.max(gap, 0.0d),
                exactNameMatch, exactExampleMatch, stableOrder, reasonCodes);
    }

    public IntentCandidateEvidence toEvidence(int modelRank) {
        return new IntentCandidateEvidence(
                node.getId(), path, lexicalScore, lexicalGap, modelRank, reasonCodes);
    }
}
