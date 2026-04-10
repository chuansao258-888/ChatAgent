package com.yulong.chatagent.intent.model;

/**
 * Controls how optional tools are exposed when one turn has a resolved intent.
 */
public enum IntentToolScopeMode {
    STRICT_TOOL_ONLY,
    AGENT_DEFAULT_WITH_INTENT_NARROWING
}
