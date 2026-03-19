package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.support.dto.AgentDTO;

/**
 * Thin wrapper around persisted agent configuration used by the runtime loader pipeline.
 *
 * @param config persisted agent configuration
 */
public record AgentDefinition(AgentDTO config) {
}
