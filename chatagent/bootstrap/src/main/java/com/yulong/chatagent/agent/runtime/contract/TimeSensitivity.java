package com.yulong.chatagent.agent.runtime.contract;

/**
 * Classifies how time-sensitive a turn's answer must be.
 *
 * <p>Used to decide whether a current/web source policy is required (acceptance
 * criterion 14 in the plan). Phase 1 only records the value; routing happens in
 * a later phase.</p>
 */
public enum TimeSensitivity {
    /** The answer is stable (policy, definition, documented procedure). */
    STATIC,
    /** The answer must reflect current/latest state (news, price, version, status). */
    CURRENT,
    /** The time need is unclear from the input alone. */
    UNKNOWN
}
