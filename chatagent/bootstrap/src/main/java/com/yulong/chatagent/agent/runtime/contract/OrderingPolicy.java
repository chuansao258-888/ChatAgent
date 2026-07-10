package com.yulong.chatagent.agent.runtime.contract;

/**
 * Ordering expectation for the turn.
 *
 * <p>Agent chat turns use {@code SESSION_SERIAL} by default so same-session
 * turns do not overlap. Phase 6 will make the local dispatch path honor this;
 * the MQ path already does.</p>
 */
public enum OrderingPolicy {
    /** Turns in the same session must run one at a time, in order. */
    SESSION_SERIAL
}
