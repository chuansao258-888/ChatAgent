package com.yulong.chatagent.agent.runtime.contract;

/**
 * Categories of clarification the turn-understanding pipeline can request.
 *
 * <p>Phase 1 only carries {@code NONE} and {@code ROUTE_CHOICE}; the execution
 * clarification kinds (SOURCE_SCOPE through TOPIC_SWITCH) are introduced when
 * Phase 4 builds the execution-clarification flow. They are declared here so the
 * {@code ClarificationPlan} shape is stable up front.</p>
 */
public enum ClarificationKind {
    /** No clarification is needed. */
    NONE,
    /** Ask the user to pick between intent-tree routing candidates (current behavior). */
    ROUTE_CHOICE,
    /** Ask which source the answer should come from (KB vs uploaded file). */
    SOURCE_SCOPE,
    /** Ask which object/entity the user means when several match. */
    OBJECT_IDENTITY,
    /** Ask which time/version the user means. */
    TIME_OR_VERSION,
    /** Ask for confirmation before a write or external-side-effect action. */
    ACTION_CONFIRMATION,
    /** No retrievable source is available; ask whether to answer generally or attach one. */
    NO_RETRIEVABLE_SOURCE,
    /** The user switched topics while a clarification was pending. */
    TOPIC_SWITCH
}
