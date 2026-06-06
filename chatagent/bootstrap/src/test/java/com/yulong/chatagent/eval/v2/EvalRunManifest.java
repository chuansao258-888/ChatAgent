package com.yulong.chatagent.eval.v2;

import java.util.List;
import java.util.Map;

public record EvalRunManifest(
        String runId,
        String suite,
        String mode,
        String timestamp,
        String gitBranch,
        String gitSha,
        String datasetId,
        String datasetHash,
        Map<String, Object> config,
        String configFingerprint,
        Map<String, String> models,
        Map<String, Object> thresholds,
        List<String> artifactFiles,
        Tuning tuning
) {
    public record Tuning(
            String parameterSpaceId,
            String experimentId,
            String trialId,
            long randomSeed
    ) {
    }
}
