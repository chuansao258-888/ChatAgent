package com.yulong.chatagent.agent.tools;

/**
 * Common contract implemented by all agent-exposed tools.
 */
public interface Tool {
    /**
     * Returns the unique tool name used in configuration and registration.
     *
     * @return tool name
     */
    String getName();

    /**
     * Returns a human-readable description of the tool capability.
     *
     * @return tool description
     */
    String getDescription();

    /**
     * Returns whether the tool is fixed or optional for agents.
     *
     * @return tool type
     */
    ToolType getType();
}
