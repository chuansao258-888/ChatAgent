package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.intent.application.IntentResolution;

/**
 * Thread-local holder for the current turn's resolved intent boundary.
 */
public final class CurrentIntentResolutionHolder {

    private static final ThreadLocal<IntentResolution> CURRENT_INTENT = new ThreadLocal<>();

    private CurrentIntentResolutionHolder() {
    }

    public static void set(IntentResolution intentResolution) {
        CURRENT_INTENT.set(intentResolution);
    }

    public static IntentResolution get() {
        return CURRENT_INTENT.get();
    }

    public static void clear() {
        CURRENT_INTENT.remove();
    }
}
