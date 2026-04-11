package com.yulong.chatagent.agent.application;

import com.yulong.chatagent.agent.tools.Tool;

import java.util.List;

/**
 * Read-only facade for tool catalog queries used by agent administration.
 */
public interface ToolFacadeService {

    /**
     * Returns every tool known to the registry.
     *
     * @return all tools
     */
    List<Tool> getAllTools();

    /**
     * Returns tools that may be optionally selected by an agent.
     *
     * @return optional tools
     */
    List<Tool> getOptionalTools();

    /**
     * Returns tools that are always attached or otherwise fixed.
     *
     * @return fixed tools
     */
    List<Tool> getFixedTools();
}
