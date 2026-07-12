package com.yulong.chatagent.admin.model.request;

/** Optimistic admin update for the local MCP effect policy. */
public record UpdateMcpToolEffectPolicyRequest(String effectPolicy, long expectedPolicyVersion) {
}
