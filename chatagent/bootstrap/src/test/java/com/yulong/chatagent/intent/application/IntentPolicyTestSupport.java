package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.contract.SourceReferenceClassifier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class IntentPolicyTestSupport {

    private IntentPolicyTestSupport() {
    }

    static IntentPolicyProfile profile() {
        return new IntentPolicyProfile(
                "v1",
                "0".repeat(64),
                "deepseek-v4-flash",
                "v1",
                "intent-features-v1",
                0.0d,
                384,
                "0".repeat(64),
                1.6d,
                0.8d,
                0.5d,
                6,
                3,
                2,
                0.98d,
                0.94d,
                0.91d
        );
    }

    static IntentUnderstandingEngine engine(IntentPolicyProperties properties,
                                            StructuredIntentClassifier classifier) {
        IntentPolicyProfileLoader loader = mock(IntentPolicyProfileLoader.class);
        when(loader.loadConfigured()).thenReturn(profile());
        return new IntentUnderstandingEngine(
                new IntentCandidateGenerator(),
                new IntentTreeQualityValidator(),
                classifier,
                new IntentDecisionPolicy(properties),
                loader,
                properties,
                new IntentSignalAnalyzer(new SourceReferenceClassifier()),
                mock(IntentPolicyMetrics.class)
        );
    }

    static StructuredIntentClassifier.Result classifierFailure() {
        return StructuredIntentClassifier.Result.failure(IntentClassifierFailure.PROVIDER_FAILURE);
    }
}
