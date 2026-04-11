package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.application.ToolFacadeService;
import com.yulong.chatagent.agent.tools.Tool;
import com.yulong.chatagent.agent.tools.ToolType;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.IntentToolScopeMode;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.mcp.runtime.McpRolloutPolicy;
import com.yulong.chatagent.mcp.runtime.McpRolloutProperties;
import com.yulong.chatagent.support.dto.AgentDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolCallbackFactoryTest {

    @Test
    void shouldPreserveLegacyGrantSemanticsWhenScopeModeIsStrictToolOnly() {
        ToolFacadeService toolFacadeService = mock(ToolFacadeService.class);
        when(toolFacadeService.getFixedTools()).thenReturn(List.of());
        when(toolFacadeService.getOptionalTools()).thenReturn(List.of(optionalTool("emailTool")));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                toolFacadeService,
                rolloutPolicy("ALL"),
                IntentToolScopeMode.STRICT_TOOL_ONLY
        );
        AgentDTO agent = AgentDTO.builder()
                .id("assistant-1")
                .allowedTools(List.of())
                .build();
        IntentResolution resolution = resolution(IntentKind.TOOL, List.of("emailTool"), List.of());

        List<ToolCallback> callbacks = factory.create(agent, resolution);

        assertThat(callbackNames(callbacks))
                .containsExactly("emailTool");
    }

    @Test
    void shouldPreserveStrictToolOnlyBehaviorWhenKbIntentIsResolved() {
        ToolFacadeService toolFacadeService = mock(ToolFacadeService.class);
        when(toolFacadeService.getFixedTools()).thenReturn(List.of(
                fixedTool("TerminateTool"),
                fixedTool("SessionFileSearchTool")
        ));
        when(toolFacadeService.getOptionalTools()).thenReturn(List.of(optionalTool("mcp_weather_get_current_weather")));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                toolFacadeService,
                rolloutPolicy("ALL"),
                IntentToolScopeMode.STRICT_TOOL_ONLY
        );
        AgentDTO agent = AgentDTO.builder()
                .id("assistant-1")
                .allowedTools(List.of("mcp_weather_get_current_weather"))
                .build();

        List<ToolCallback> callbacks = factory.create(agent, resolution(IntentKind.KB, List.of(), List.of("kb-1")));

        assertThat(callbackNames(callbacks))
                .containsExactlyInAnyOrder("TerminateTool", "SessionFileSearchTool");
    }

    @Test
    void shouldInheritAgentDefaultPoolWhenIntentIsAbsentUnderIntentNarrowingMode() {
        ToolFacadeService toolFacadeService = mock(ToolFacadeService.class);
        when(toolFacadeService.getFixedTools()).thenReturn(List.of(fixedTool("TerminateTool")));
        when(toolFacadeService.getOptionalTools()).thenReturn(List.of(optionalTool("toolA"), optionalTool("toolB")));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                toolFacadeService,
                rolloutPolicy("ALL"),
                IntentToolScopeMode.AGENT_DEFAULT_WITH_INTENT_NARROWING
        );
        AgentDTO agent = AgentDTO.builder()
                .id("assistant-1")
                .allowedTools(List.of("toolA", "toolB"))
                .build();

        List<ToolCallback> callbacks = factory.create(agent, null);

        assertThat(callbackNames(callbacks))
                .containsExactlyInAnyOrder("TerminateTool", "toolA", "toolB");
    }

    @Test
    void shouldExposeAgentDefaultToolsForKbIntentWhenIntentNarrowingModeIsEnabled() {
        ToolFacadeService toolFacadeService = mock(ToolFacadeService.class);
        when(toolFacadeService.getFixedTools()).thenReturn(List.of(
                fixedTool("TerminateTool"),
                fixedTool("SessionFileSearchTool")
        ));
        when(toolFacadeService.getOptionalTools()).thenReturn(List.of(optionalTool("mcp_weather_get_current_weather")));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                toolFacadeService,
                rolloutPolicy("ALL"),
                IntentToolScopeMode.AGENT_DEFAULT_WITH_INTENT_NARROWING
        );
        AgentDTO agent = AgentDTO.builder()
                .id("assistant-1")
                .allowedTools(List.of("mcp_weather_get_current_weather"))
                .build();

        List<ToolCallback> callbacks = factory.create(agent, resolution(IntentKind.KB, List.of(), List.of("kb-1")));

        assertThat(callbackNames(callbacks))
                .containsExactlyInAnyOrder("TerminateTool", "SessionFileSearchTool", "mcp_weather_get_current_weather");
    }

    @Test
    void shouldFilterSessionFileSearchToolOutsideKbIntentWhenIntentNarrowingModeIsEnabled() {
        ToolFacadeService toolFacadeService = mock(ToolFacadeService.class);
        when(toolFacadeService.getFixedTools()).thenReturn(List.of(
                fixedTool("TerminateTool"),
                fixedTool("SessionFileSearchTool")
        ));
        when(toolFacadeService.getOptionalTools()).thenReturn(List.of(optionalTool("mcp_weather_get_current_weather")));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                toolFacadeService,
                rolloutPolicy("ALL"),
                IntentToolScopeMode.AGENT_DEFAULT_WITH_INTENT_NARROWING
        );
        AgentDTO agent = AgentDTO.builder()
                .id("assistant-1")
                .allowedTools(List.of("mcp_weather_get_current_weather"))
                .build();

        List<ToolCallback> callbacks = factory.create(agent, resolution(IntentKind.CLARIFY, List.of(), List.of()));

        assertThat(callbackNames(callbacks))
                .containsExactlyInAnyOrder("TerminateTool", "mcp_weather_get_current_weather");
    }

    @Test
    void shouldHideSessionFileSearchToolWhenIntentIsAbsentButOptionalToolsAreAvailable() {
        ToolFacadeService toolFacadeService = mock(ToolFacadeService.class);
        when(toolFacadeService.getFixedTools()).thenReturn(List.of(
                fixedTool("TerminateTool"),
                fixedTool("SessionFileSearchTool")
        ));
        when(toolFacadeService.getOptionalTools()).thenReturn(List.of(optionalTool("mcp_weather_get_current_weather")));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                toolFacadeService,
                rolloutPolicy("ALL"),
                IntentToolScopeMode.AGENT_DEFAULT_WITH_INTENT_NARROWING
        );
        AgentDTO agent = AgentDTO.builder()
                .id("assistant-1")
                .allowedTools(List.of("mcp_weather_get_current_weather"))
                .build();

        List<ToolCallback> callbacks = factory.create(agent, null);

        assertThat(callbackNames(callbacks))
                .containsExactlyInAnyOrder("TerminateTool", "mcp_weather_get_current_weather");
    }

    @Test
    void shouldNarrowToolIntentToIntersectionWithoutGrantingNewTools() {
        ToolFacadeService toolFacadeService = mock(ToolFacadeService.class);
        when(toolFacadeService.getFixedTools()).thenReturn(List.of(fixedTool("TerminateTool")));
        when(toolFacadeService.getOptionalTools()).thenReturn(List.of(
                optionalTool("toolA"),
                optionalTool("toolB"),
                optionalTool("toolC"),
                optionalTool("toolD")
        ));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                toolFacadeService,
                rolloutPolicy("ALL"),
                IntentToolScopeMode.AGENT_DEFAULT_WITH_INTENT_NARROWING
        );
        AgentDTO agent = AgentDTO.builder()
                .id("assistant-1")
                .allowedTools(List.of("toolA", "toolB", "toolC"))
                .build();
        IntentResolution resolution = resolution(IntentKind.TOOL, List.of("toolB", "toolD"), List.of());

        List<ToolCallback> callbacks = factory.create(agent, resolution);

        assertThat(callbackNames(callbacks))
                .containsExactlyInAnyOrder("TerminateTool", "toolB");
    }

    @Test
    void shouldInheritAgentDefaultToolsWhenToolIntentLeavesAllowedToolsEmpty() {
        ToolFacadeService toolFacadeService = mock(ToolFacadeService.class);
        when(toolFacadeService.getFixedTools()).thenReturn(List.of(fixedTool("TerminateTool")));
        when(toolFacadeService.getOptionalTools()).thenReturn(List.of(optionalTool("toolA")));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                toolFacadeService,
                rolloutPolicy("ALL"),
                IntentToolScopeMode.AGENT_DEFAULT_WITH_INTENT_NARROWING
        );
        AgentDTO agent = AgentDTO.builder()
                .id("assistant-1")
                .allowedTools(List.of("toolA"))
                .build();

        List<ToolCallback> callbacks = factory.create(agent, resolution(IntentKind.TOOL, List.of(), List.of()));

        assertThat(callbackNames(callbacks))
                .containsExactlyInAnyOrder("TerminateTool", "toolA");
    }

    @Test
    void shouldGrantIntentToolsWhenAgentDefaultPoolIsEmpty() {
        ToolFacadeService toolFacadeService = mock(ToolFacadeService.class);
        when(toolFacadeService.getFixedTools()).thenReturn(List.of(fixedTool("TerminateTool")));
        when(toolFacadeService.getOptionalTools()).thenReturn(List.of(optionalTool("toolB")));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                toolFacadeService,
                rolloutPolicy("ALL"),
                IntentToolScopeMode.AGENT_DEFAULT_WITH_INTENT_NARROWING
        );
        AgentDTO agent = AgentDTO.builder()
                .id("assistant-1")
                .allowedTools(List.of())
                .build();

        List<ToolCallback> callbacks = factory.create(agent, resolution(IntentKind.TOOL, List.of("toolB"), List.of()));

        assertThat(callbackNames(callbacks))
                .containsExactlyInAnyOrder("TerminateTool", "toolB");
    }

    @Test
    void shouldHideMcpToolsWhenAgentIsOutsideRolloutAllowlist() {
        ToolFacadeService toolFacadeService = mock(ToolFacadeService.class);
        when(toolFacadeService.getFixedTools()).thenReturn(List.of());
        when(toolFacadeService.getOptionalTools()).thenReturn(List.of(optionalTool("mcp_google_search")));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                toolFacadeService,
                rolloutPolicy("AGENT_ALLOWLIST", "assistant-2"),
                IntentToolScopeMode.AGENT_DEFAULT_WITH_INTENT_NARROWING
        );
        AgentDTO agent = AgentDTO.builder()
                .id("assistant-1")
                .allowedTools(List.of("mcp_google_search"))
                .build();

        List<ToolCallback> callbacks = factory.create(agent, null);

        assertThat(callbacks).isEmpty();
    }

    private static List<String> callbackNames(List<ToolCallback> callbacks) {
        return callbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    private static IntentResolution resolution(IntentKind intentKind,
                                               List<String> allowedTools,
                                               List<String> scopedKbIds) {
        return new IntentResolution(
                intentKind,
                List.of(),
                scopedKbIds,
                ScopePolicy.STRICT,
                allowedTools,
                null
        );
    }

    private static McpRolloutPolicy rolloutPolicy(String mode, String... allowedAgentIds) {
        McpRolloutProperties properties = new McpRolloutProperties();
        properties.setMode(mode);
        properties.setAllowedAgentIds(List.of(allowedAgentIds));
        return new McpRolloutPolicy(properties);
    }

    private static Tool fixedTool(String name) {
        return new NamedTool(name, ToolType.FIXED);
    }

    private static Tool optionalTool(String name) {
        return new NamedTool(name, ToolType.OPTIONAL);
    }

    private static final class NamedTool implements Tool, DirectToolCallbackSource {

        private final String name;
        private final ToolType type;
        private final ToolCallback callback;

        private NamedTool(String name, ToolType type) {
            this.name = name;
            this.type = type;
            this.callback = new ToolCallback() {
                private final ToolDefinition definition = ToolDefinition.builder()
                        .name(name)
                        .description(name + " description")
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
        }

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

        @Override
        public List<ToolCallback> getToolCallbacks() {
            return List.of(callback);
        }
    }
}
