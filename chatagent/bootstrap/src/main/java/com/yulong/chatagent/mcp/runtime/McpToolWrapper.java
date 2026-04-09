package com.yulong.chatagent.mcp.runtime;

import com.yulong.chatagent.agent.runtime.DirectToolCallbackSource;
import com.yulong.chatagent.agent.tools.Tool;
import com.yulong.chatagent.agent.tools.ToolType;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Admin/runtime wrapper that exposes one remote MCP tool as one optional ChatAgent tool.
 */
public class McpToolWrapper implements Tool, DirectToolCallbackSource {

    private final String name;
    private final String description;
    private final List<ToolCallback> toolCallbacks;

    public McpToolWrapper(String name, String description, List<ToolCallback> toolCallbacks) {
        this.name = name;
        this.description = description;
        this.toolCallbacks = toolCallbacks == null ? List.of() : List.copyOf(toolCallbacks);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @Override
    public List<ToolCallback> getToolCallbacks() {
        return toolCallbacks;
    }
}
