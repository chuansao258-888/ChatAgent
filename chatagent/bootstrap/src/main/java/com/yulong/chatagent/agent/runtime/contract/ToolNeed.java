package com.yulong.chatagent.agent.runtime.contract;

/**
 * Classifies whether a turn needs tool execution, independent of the concrete
 * allowed-tool set already carried by {@code IntentResolution}.
 */
public enum ToolNeed {
    /** No tool call is expected for this turn. */
    NONE,
    /** A tool may help but is not required to answer. */
    OPTIONAL,
    /** The turn cannot be answered without calling a tool. */
    REQUIRED
}
