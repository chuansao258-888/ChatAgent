package com.yulong.chatagent.intent.application;

import java.util.List;

/** Safe ranked evidence for one allowlisted intent-tree candidate. */
public record IntentCandidateEvidence(
        String nodeId,
        String path,
        double lexicalScore,
        double lexicalGap,
        int modelRank,
        List<String> reasonCodes
) {
    public IntentCandidateEvidence {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        modelRank = Math.max(modelRank, 0);
    }
}
