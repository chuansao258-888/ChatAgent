package com.yulong.chatagent.intent.application;

/**
 * Temporary clarification-state storage.
 */
public interface PendingIntentResolutionStore {

    PendingIntentResolution get(String sessionId);

    void save(PendingIntentResolution pendingIntentResolution);

    void delete(String sessionId);
}
