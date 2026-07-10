package com.yulong.chatagent.agent.runtime.contract;

/**
 * Classifies the risk profile of the action a turn requests.
 *
 * <p>Used to decide whether confirmation is required before tool execution
 * (acceptance criterion 15 in the plan). Phase 1 records the value only.</p>
 */
public enum ActionRisk {
    /** The turn is read-only and has no side effects. */
    READ_ONLY,
    /** The turn performs a write action inside the system. */
    WRITE_ACTION,
    /** The turn triggers an external side effect (notification, API call, send). */
    EXTERNAL_SIDE_EFFECT
}
