package com.yulong.chatagent.agent.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves a requested execution mode into the mode the runtime should actually use.
 */
@Slf4j
@Component
public class AgentExecutionModeResolver {

    private final AgentRunPolicyProperties policyProperties;

    public AgentExecutionModeResolver(AgentRunPolicyProperties policyProperties) {
        this.policyProperties = policyProperties;
    }

    public AgentExecutionMode resolve(AgentExecutionMode requestedMode) {
        return resolve(requestedMode, this.policyProperties);
    }

    public static AgentExecutionMode resolve(AgentExecutionMode requestedMode,
                                             AgentRunPolicyProperties policyProperties) {
        if (policyProperties == null) {
            if (requestedMode == AgentExecutionMode.DEEPTHINK) {
                log.warn("DeepThink requested but AgentRunPolicyProperties is unavailable. Falling back to REACT.");
            }
            return AgentExecutionMode.REACT;
        }
        AgentExecutionMode mode = requestedMode != null
                ? requestedMode
                : policyProperties.getDefaultMode();

        if (mode == null) {
            mode = AgentExecutionMode.REACT;
        }

        if (mode == AgentExecutionMode.DEEPTHINK
                && !policyProperties.getDeepthink().isEnabled()) {
            log.info("DeepThink requested but disabled by configuration. Falling back to REACT.");
            return AgentExecutionMode.REACT;
        }

        return mode;
    }
}
