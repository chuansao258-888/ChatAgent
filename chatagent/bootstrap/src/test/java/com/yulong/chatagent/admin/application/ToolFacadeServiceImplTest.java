package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.agent.tools.TerminateTool;
import com.yulong.chatagent.agent.tools.Tool;
import com.yulong.chatagent.agent.tools.ToolType;
import com.yulong.chatagent.mcp.runtime.McpRuntimeToolRegistry;
import com.yulong.chatagent.mcp.runtime.McpToolWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolFacadeServiceImplTest {

    @Test
    void shouldKeepLocalFixedToolsAndAppendMcpOptionalTools() {
        Tool localOptional = new Tool() {
            @Override
            public String getName() {
                return "emailTool";
            }

            @Override
            public String getDescription() {
                return "Send email";
            }

            @Override
            public ToolType getType() {
                return ToolType.OPTIONAL;
            }
        };
        McpRuntimeToolRegistry runtimeToolRegistry = mock(McpRuntimeToolRegistry.class);
        when(runtimeToolRegistry.getOptionalTools()).thenReturn(List.of(new McpToolWrapper(
                "mcp_google_search",
                "Search through MCP",
                List.of(simpleCallback("mcp_google_search"))
        )));

        ToolFacadeServiceImpl facade = new ToolFacadeServiceImpl(
                List.of(new TerminateTool(), localOptional),
                runtimeToolRegistry
        );

        assertThat(facade.getFixedTools())
                .extracting(Tool::getName)
                .containsExactly("terminate");
        assertThat(facade.getOptionalTools())
                .extracting(Tool::getName)
                .containsExactly("emailTool", "mcp_google_search");
        assertThat(facade.getAllTools())
                .extracting(Tool::getName)
                .containsExactly("terminate", "emailTool", "mcp_google_search");
    }

    private ToolCallback simpleCallback(String name) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(name)
                    .description("test")
                    .inputSchema("{}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }
        };
    }
}
