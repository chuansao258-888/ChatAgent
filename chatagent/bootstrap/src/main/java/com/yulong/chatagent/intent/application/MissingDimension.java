package com.yulong.chatagent.intent.application;

/** Information that is still required before a turn can execute safely. */
public enum MissingDimension {
    SOURCE,
    OBJECT,
    TIME_OR_VERSION,
    ACTION,
    ORDER,
    CONFIRMATION
}
