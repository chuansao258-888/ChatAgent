package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.agent.application.ToolFacadeService;

import com.yulong.chatagent.agent.tools.Tool;
import com.yulong.chatagent.agent.tools.ToolType;
import com.yulong.chatagent.agent.tools.WebSearchTools;
import com.yulong.chatagent.mcp.runtime.McpRuntimeToolRegistry;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of tool catalog queries backed by Spring-managed tool beans.
 */
@Service
@AllArgsConstructor
public class ToolFacadeServiceImpl implements ToolFacadeService {

    private final List<Tool> tools;
    private final McpRuntimeToolRegistry mcpRuntimeToolRegistry;

    @Override
    public List<Tool> getAllTools() {
        List<Tool> allTools = new ArrayList<>(tools.stream()
                .filter(this::isVisible)
                .toList());
        allTools.addAll(mcpRuntimeToolRegistry.getOptionalTools());
        return List.copyOf(allTools);
    }

    @Override
    public List<Tool> getOptionalTools() {
        List<Tool> optionalTools = new ArrayList<>(getToolsByType(ToolType.OPTIONAL));
        optionalTools.addAll(mcpRuntimeToolRegistry.getOptionalTools());
        return List.copyOf(optionalTools);
    }

    @Override
    public List<Tool> getFixedTools() {
        return getToolsByType(ToolType.FIXED);
    }

    /**
     * Filters the full tool list by tool type.
     *
     * @param type target tool type
     * @return matching tools
     */
    private List<Tool> getToolsByType(ToolType type) {
        return tools.stream()
                .filter(tool -> tool.getType().equals(type))
                .filter(this::isVisible)
                .toList();
    }

    private boolean isVisible(Tool tool) {
        if (tool instanceof WebSearchTools webSearchTools) {
            return webSearchTools.isAvailable();
        }
        return true;
    }
}
