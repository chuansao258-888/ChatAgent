package com.yulong.chatagent.intent.model.vo;

/** View of an intent-graph version and whether it is the active one. */
public record IntentVersionVO(Integer version, boolean active) {
}
