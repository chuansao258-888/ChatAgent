package com.yulong.chatagent.intent.application;

/** Immutable, calibration-bound policy values loaded from a versioned resource. */
public record IntentPolicyProfile(
        String version,
        String corpusManifestHash,
        String classifierModelId,
        String promptVersion,
        String featureVersion,
        double classifierTemperature,
        int classifierMaxTokens,
        String treeFixtureManifestHash,
        double acceptScore,
        double clarifyScore,
        double ambiguityGap,
        int maxClassifierCandidates,
        int maxClarificationCandidates,
        int minimumReviewedExamples,
        double deterministicExactConfidence,
        double classifierAgreementConfidence,
        double heuristicAcceptConfidence
) {
}
