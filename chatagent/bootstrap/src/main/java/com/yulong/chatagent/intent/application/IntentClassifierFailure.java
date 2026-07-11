package com.yulong.chatagent.intent.application;

/** Content-free failure reasons for the structured intent classifier. */
public enum IntentClassifierFailure {
    NONE,
    BLANK_RESPONSE,
    MALFORMED_RESPONSE,
    UNKNOWN_CANDIDATE_ID,
    INVALID_CANDIDATE_COMBINATION,
    TIMEOUT,
    PROVIDER_FAILURE
}
