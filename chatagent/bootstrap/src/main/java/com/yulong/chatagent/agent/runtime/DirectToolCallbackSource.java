package com.yulong.chatagent.agent.runtime;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Optional runtime seam for tools that already materialize concrete callbacks.
 */
public interface DirectToolCallbackSource {

    List<ToolCallback> getToolCallbacks();
}
