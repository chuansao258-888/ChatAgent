package com.yulong.chatagent.mcp.runtime;

import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

/**
 * Builds Spring AI {@link ToolDefinition} instances from cached MCP catalog rows.
 */
@Component
public class McpToolDefinitionFactory {

    public ToolDefinition create(McpToolCatalogDTO toolCatalog) {
        return ToolDefinition.builder()
                .name(toolCatalog.getExposedModelName())
                .description(toolCatalog.getToolDescription())
                .inputSchema(toolCatalog.getSchemaJson())
                .build();
    }
}
