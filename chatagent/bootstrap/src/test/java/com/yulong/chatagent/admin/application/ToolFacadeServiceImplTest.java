package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.agent.tools.Tool;
import com.yulong.chatagent.agent.tools.ToolType;
import com.yulong.chatagent.agent.tools.WebSearchTools;
import com.yulong.chatagent.mcp.runtime.McpRuntimeToolRegistry;
import com.yulong.chatagent.websearch.WebSearchProperties;
import com.yulong.chatagent.websearch.WebSearchClient;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolFacadeServiceImplTest {

    @Test
    void shouldHideWebSearchToolWhenDisabled() {
        ToolFacadeServiceImpl facade = facadeWith(webSearchTool(false, true));

        assertThat(facade.getOptionalTools())
                .extracting(Tool::getName)
                .doesNotContain("webSearchTool");
        assertThat(facade.getAllTools())
                .extracting(Tool::getName)
                .doesNotContain("webSearchTool");
    }

    @Test
    void shouldHideWebSearchToolWhenProviderIsUnreachable() {
        ToolFacadeServiceImpl facade = facadeWith(webSearchTool(true, false));

        assertThat(facade.getOptionalTools())
                .extracting(Tool::getName)
                .doesNotContain("webSearchTool");
    }

    @Test
    void shouldExposeWebSearchToolWhenEnabledAndReachable() {
        ToolFacadeServiceImpl facade = facadeWith(webSearchTool(true, true));

        assertThat(facade.getOptionalTools())
                .extracting(Tool::getName)
                .contains("webSearchTool");
        assertThat(facade.getAllTools())
                .extracting(Tool::getName)
                .contains("webSearchTool");
    }

    @Test
    void shouldKeepOtherLocalAndMcpOptionalToolsVisible() {
        Tool localOptional = namedTool("localTool", ToolType.OPTIONAL);
        Tool fixedTool = namedTool("TerminateTool", ToolType.FIXED);
        Tool mcpTool = namedTool("mcp_weather", ToolType.OPTIONAL);
        McpRuntimeToolRegistry registry = mock(McpRuntimeToolRegistry.class);
        when(registry.getOptionalTools()).thenReturn(List.of(mcpTool));

        ToolFacadeServiceImpl facade = new ToolFacadeServiceImpl(
                List.of(fixedTool, localOptional, webSearchTool(false, true)),
                registry
        );

        assertThat(facade.getFixedTools())
                .extracting(Tool::getName)
                .containsExactly("TerminateTool");
        assertThat(facade.getOptionalTools())
                .extracting(Tool::getName)
                .containsExactly("localTool", "mcp_weather");
        assertThat(facade.getAllTools())
                .extracting(Tool::getName)
                .containsExactly("TerminateTool", "localTool", "mcp_weather");
    }

    private static ToolFacadeServiceImpl facadeWith(Tool tool) {
        McpRuntimeToolRegistry registry = mock(McpRuntimeToolRegistry.class);
        when(registry.getOptionalTools()).thenReturn(List.of());
        return new ToolFacadeServiceImpl(List.of(tool), registry);
    }

    private static WebSearchTools webSearchTool(boolean enabled, boolean reachable) {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setEnabled(enabled);
        properties.setBraveApiKey(reachable ? "test-key" : "");
        return new WebSearchTools(
                properties,
                mock(WebSearchClient.class)
        );
    }

    private static Tool namedTool(String name, ToolType type) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name + " description";
            }

            @Override
            public ToolType getType() {
                return type;
            }
        };
    }
}
