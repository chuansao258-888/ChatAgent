package com.yulong.chatagent.agent.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * User-selected agent execution mode for one conversation turn.
 */
public enum AgentExecutionMode {
    REACT,
    DEEPTHINK;

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionMode.class);

    @JsonCreator
    public static AgentExecutionMode fromJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AgentExecutionMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            log.warn("Unknown agent execution mode '{}', defaulting to REACT", value);
            return REACT;
        }
    }

    @JsonValue
    public String jsonValue() {
        return name();
    }
}
