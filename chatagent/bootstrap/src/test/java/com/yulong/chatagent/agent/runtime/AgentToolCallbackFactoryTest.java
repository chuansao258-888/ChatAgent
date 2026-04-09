package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.admin.application.ToolFacadeService;
import com.yulong.chatagent.agent.tools.Tool;
import com.yulong.chatagent.agent.tools.ToolType;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.mcp.runtime.McpRolloutPolicy;
import com.yulong.chatagent.mcp.runtime.McpRolloutProperties;
import com.yulong.chatagent.support.dto.AgentDTO;
import org.springframework.ai.chat.model.ToolContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolCallbackFactoryTest {

    @Test
    void shouldAllowIntentToolsWhenAgentHasNoOptionalRestrictions() {
        ToolFacadeService toolFacadeService = mock(ToolFacadeService.class);
        when(toolFacadeService.getFixedTools()).thenReturn(List.of());
        when(toolFacadeService.getOptionalTools()).thenReturn(List.of(new TestOptionalTool()));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(toolFacadeService, rolloutPolicy("ALL"));
        AgentDTO agent = AgentDTO.builder()
                .id("assistant-1")
                .allowedTools(List.of())
                .build();
        IntentResolution resolution = new IntentResolution(
                IntentKind.TOOL,
                List.of(),
                List.of(),
                ScopePolicy.STRICT,
                List.of("emailTool"),
                null
        );

        List<ToolCallback> callbacks = factory.create(agent, resolution);

        assertThat(callbacks)
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("sendEmail");
    }

    @Test
    void shouldRegisterDirectToolCallbackSourcesWithoutReflection() {
        ToolFacadeService toolFacadeService = mock(ToolFacadeService.class);
        when(toolFacadeService.getFixedTools()).thenReturn(List.of());
        when(toolFacadeService.getOptionalTools()).thenReturn(List.of(new DirectOptionalTool()));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(toolFacadeService, rolloutPolicy("ALL"));
        AgentDTO agent = AgentDTO.builder()
                .id("assistant-1")
                .allowedTools(List.of("mcp_google_search"))
                .build();

        List<ToolCallback> callbacks = factory.create(agent, null);

        assertThat(callbacks)
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("mcp_google_search");
    }

    @Test
    void shouldHideMcpToolsWhenAgentIsOutsideRolloutAllowlist() {
        ToolFacadeService toolFacadeService = mock(ToolFacadeService.class);
        when(toolFacadeService.getFixedTools()).thenReturn(List.of());
        when(toolFacadeService.getOptionalTools()).thenReturn(List.of(new DirectOptionalTool()));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(toolFacadeService, rolloutPolicy("AGENT_ALLOWLIST", "assistant-2"));
        AgentDTO agent = AgentDTO.builder()
                .id("assistant-1")
                .allowedTools(List.of("mcp_google_search"))
                .build();

        List<ToolCallback> callbacks = factory.create(agent, null);

        assertThat(callbacks).isEmpty();
    }

    private static McpRolloutPolicy rolloutPolicy(String mode, String... allowedAgentIds) {
        McpRolloutProperties properties = new McpRolloutProperties();
        properties.setMode(mode);
        properties.setAllowedAgentIds(List.of(allowedAgentIds));
        return new McpRolloutPolicy(properties);
    }

    static class TestOptionalTool implements Tool {

        @Override
        public String getName() {
            return "emailTool";
        }

        @Override
        public String getDescription() {
            return "Email tool";
        }

        @Override
        public ToolType getType() {
            return ToolType.OPTIONAL;
        }

        @org.springframework.ai.tool.annotation.Tool(name = "sendEmail", description = "Send email")
        public String sendEmail(String to) {
            return "sent:" + to;
        }
    }

    static final class DirectOptionalTool implements Tool, DirectToolCallbackSource {

        private final ToolCallback callback = new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name("mcp_google_search")
                    .description("Search via MCP")
                    .inputSchema("{}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return call(toolInput, null);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return "{\"status\":\"ok\"}";
            }
        };

        @Override
        public String getName() {
            return "mcp_google_search";
        }

        @Override
        public String getDescription() {
            return "Search through MCP";
        }

        @Override
        public ToolType getType() {
            return ToolType.OPTIONAL;
        }

        @Override
        public List<ToolCallback> getToolCallbacks() {
            return List.of(callback);
        }
    }
}
