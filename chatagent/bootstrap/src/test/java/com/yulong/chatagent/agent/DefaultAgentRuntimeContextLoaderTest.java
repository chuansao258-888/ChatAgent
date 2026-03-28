package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.runtime.AgentDefinition;
import com.yulong.chatagent.agent.runtime.AgentDefinitionLoader;
import com.yulong.chatagent.agent.runtime.AgentMemoryLoader;
import com.yulong.chatagent.agent.runtime.AgentSessionFileSummaryResolver;
import com.yulong.chatagent.agent.runtime.AgentSessionSummaryResolver;
import com.yulong.chatagent.agent.runtime.AgentToolCallbackFactory;
import com.yulong.chatagent.agent.runtime.AgentUserProfileSummaryResolver;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAgentRuntimeContextLoaderTest {

    @Mock
    private AgentDefinitionLoader agentDefinitionLoader;

    @Mock
    private AgentMemoryLoader agentMemoryLoader;

    @Mock
    private AgentSessionFileSummaryResolver sessionFileSummaryResolver;

    @Mock
    private AgentSessionSummaryResolver sessionSummaryResolver;

    @Mock
    private AgentUserProfileSummaryResolver userProfileSummaryResolver;

    @Mock
    private AgentToolCallbackFactory agentToolCallbackFactory;

    @Mock
    private ToolCallback toolCallback;

    private DefaultAgentRuntimeContextLoader loader;

    @BeforeEach
    void setUp() {
        loader = new DefaultAgentRuntimeContextLoader(
                agentDefinitionLoader,
                agentMemoryLoader,
                sessionFileSummaryResolver,
                sessionSummaryResolver,
                userProfileSummaryResolver,
                agentToolCallbackFactory
        );
    }

    @Test
    void shouldInjectHistoricalSummaryBeforeIntentContext() {
        AgentDTO agent = AgentDTO.builder()
                .id("agent-1")
                .name("Support")
                .description("Support agent")
                .systemPrompt("Base prompt")
                .model(AgentDTO.ModelType.DEEPSEEK_CHAT)
                .chatOptions(AgentDTO.ChatOptions.builder()
                        .messageLength(12)
                        .tokenBudget(4000)
                        .build())
                .build();
        IntentResolution intentResolution = new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().name("HR").build(), IntentNodeDTO.builder().name("Leave").build()),
                List.of("kb-1"),
                ScopePolicy.STRICT,
                List.of("searchTool"),
                null
        );

        when(agentDefinitionLoader.load("agent-1")).thenReturn(new AgentDefinition(agent));
        when(agentMemoryLoader.load("session-1", agent)).thenReturn(List.of(new UserMessage("hello")));
        when(sessionFileSummaryResolver.resolve(agent, "session-1")).thenReturn("Attached policy.pdf");
        when(sessionSummaryResolver.resolve("session-1")).thenReturn("L2 summary");
        when(userProfileSummaryResolver.resolve("session-1")).thenReturn("Known employee");
        when(agentToolCallbackFactory.create(agent, intentResolution)).thenReturn(List.of(toolCallback));

        AgentRuntimeContext context = loader.load("agent-1", "session-1", intentResolution, "leave request process");

        String systemPrompt = context.systemPrompt();
        assertThat(systemPrompt).contains("[Historical Context Summary]");
        assertThat(systemPrompt).contains("[Intent Routing Context]");
        assertThat(systemPrompt).contains("[Session Context]");
        assertThat(systemPrompt.indexOf("[Historical Context Summary]"))
                .isLessThan(systemPrompt.indexOf("[Intent Routing Context]"));
        assertThat(systemPrompt.indexOf("[Intent Routing Context]"))
                .isLessThan(systemPrompt.indexOf("[Session Context]"));
    }
}
