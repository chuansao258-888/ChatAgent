package com.yulong.chatagent.agent.runtime.contract;

/**
 * Structured intent label set for {@code TurnAnalysis}.
 *
 * <p>This is intentionally broader than {@code IntentKind} (KB/TOOL/SYSTEM/CLARIFY),
 * which stays the routing-level classification. {@code IntentLabel} lets the
 * understanding layer express secondary intents such as comparison or
 * currentness that a single route kind cannot.</p>
 */
public enum IntentLabel {
    /** General conversational turn with no specific source or tool need. */
    GENERAL_CHAT,
    /** Knowledge-base question answering. */
    KB_QA,
    /** Uploaded/session-file question answering. */
    FILE_QA,
    /** Current/latest/news/version/time-sensitive request. */
    CURRENTNESS,
    /** Tool or action request. */
    ACTION_REQUEST,
    /** Request to compare across sources or objects. */
    COMPARE,
    /** Request to summarize content. */
    SUMMARIZE,
    /** Request to extract specific facts. */
    EXTRACT,
    /** Request to verify a claim or check consistency. */
    VERIFY,
    /** Ambiguous follow-up that needs clarification or context. */
    AMBIGUOUS_FOLLOW_UP,
    /** A new topic that switches away from the previous turn. */
    TOPIC_SWITCH,
    /** Multi-intent task combining more than one of the above. */
    MULTI_INTENT
}
