package com.yulong.chatagent.intent.application;

/**
 * Loads the active published intent snapshot for one assistant.
 */
public interface IntentTreeCacheManager {

    IntentTreeSnapshot loadActiveSnapshot(String agentId);

    void evictActiveSnapshot(String agentId);

    IntentTreeSnapshot refreshActiveSnapshot(String agentId);
}
