package com.yulong.chatagent.intent.application;

/** Source of the evidence that produced an intent decision. */
public enum IntentDecisionSource {
    DETERMINISTIC,
    CLASSIFIER,
    CALIBRATED_POLICY,
    SAFE_FALLBACK,
    LEGACY_SHADOW
}
