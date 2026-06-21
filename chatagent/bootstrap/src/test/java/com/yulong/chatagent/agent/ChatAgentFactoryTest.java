package com.yulong.chatagent.agent;

import com.yulong.chatagent.TestPromptLoader;
import com.yulong.chatagent.agent.deepthink.DeepThinkRuntimeEngine;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.AgentRunContext;
import com.yulong.chatagent.agent.runtime.AgentRunPolicyProperties;
import com.yulong.chatagent.agent.runtime.AgentRuntimeEngine;
import com.yulong.chatagent.agent.runtime.ReactRuntimeEngine;
import com.yulong.chatagent.chat.routing.LLMService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatAgentFactoryTest {

    @Test
    void shouldCreateAgentUsingRuntimeContext() {
        LLMService llmService = mock(LLMService.class);
        AgentRuntimeContextLoader contextLoader = mock(AgentRuntimeContextLoader.class);
        AgentMessageBridge messageBridge = mock(AgentMessageBridge.class);
        Message message = mock(Message.class);
        ToolCallback toolCallback = mock(ToolCallback.class);

        AgentRuntimeContext context = new AgentRuntimeContext(
                "agent-1",
                "Support",
                "support agent",
                "system prompt",
                "glm-4.6",
                12,
                List.of(message),
                List.of(toolCallback),
                "session file summary",
                "session summary",
                "relevant long-term memory",
                AgentExecutionMode.REACT
        );

        when(contextLoader.load("agent-1", "session-1", null, null, null, null)).thenReturn(context);

        ChatAgentFactory factory = new ChatAgentFactory(
                TestPromptLoader.create(), llmService, contextLoader, messageBridge,
                new AgentRunPolicyProperties()
        );

        ChatAgent chatAgent = factory.create("agent-1", "session-1");

        assertThat(ReflectionTestUtils.getField(chatAgent, "agentId")).isEqualTo("agent-1");
        assertThat(ReflectionTestUtils.getField(chatAgent, "name")).isEqualTo("Support");
        // ChatAgent now delegates to runtime engine, verify the runContext is set
        AgentRunContext runContext = (AgentRunContext) ReflectionTestUtils.getField(chatAgent, "runContext");
        assertThat(runContext).isNotNull();
        assertThat(runContext.sessionFileSummary()).isEqualTo("session file summary");
        assertThat(runContext.relevantLongTermMemories()).isEqualTo("relevant long-term memory");
        assertThat(runContext.executionMode()).isEqualTo(AgentExecutionMode.REACT);

        // REACT mode should select ReactRuntimeEngine
        AgentRuntimeEngine engine = (AgentRuntimeEngine) ReflectionTestUtils.getField(chatAgent, "runtimeEngine");
        assertThat(engine).isInstanceOf(ReactRuntimeEngine.class);
    }

    @Test
    void shouldCreateAgentWithExplicitDeepThinkMode() {
        LLMService llmService = mock(LLMService.class);
        AgentRuntimeContextLoader contextLoader = mock(AgentRuntimeContextLoader.class);
        AgentMessageBridge messageBridge = mock(AgentMessageBridge.class);

        AgentRuntimeContext context = new AgentRuntimeContext(
                "agent-1",
                "Support",
                "support agent",
                "system prompt",
                "glm-4.6",
                12,
                List.of(),
                List.of(),
                "session file summary",
                "session summary",
                "relevant long-term memory",
                AgentExecutionMode.DEEPTHINK
        );

        when(contextLoader.load("agent-1", "session-1", null, null, AgentExecutionMode.DEEPTHINK, null))
                .thenReturn(context);

        ChatAgentFactory factory = new ChatAgentFactory(
                TestPromptLoader.create(), llmService, contextLoader, messageBridge,
                new AgentRunPolicyProperties()
        );

        ChatAgent chatAgent = factory.create(
                "agent-1",
                "session-1",
                null,
                null,
                null,
                "user-1",
                AgentExecutionMode.DEEPTHINK
        );

        AgentRunContext runContext = (AgentRunContext) ReflectionTestUtils.getField(chatAgent, "runContext");
        assertThat(runContext.executionMode()).isEqualTo(AgentExecutionMode.DEEPTHINK);
        verify(contextLoader).load("agent-1", "session-1", null, null, AgentExecutionMode.DEEPTHINK, null);

        // DEEPTHINK mode should select DeepThinkRuntimeEngine
        AgentRuntimeEngine deepEngine = (AgentRuntimeEngine) ReflectionTestUtils.getField(chatAgent, "runtimeEngine");
        assertThat(deepEngine).isInstanceOf(DeepThinkRuntimeEngine.class);
    }

    @Test
    void shouldOmitLongTermMemoryWhenRuntimeContextHasNone() {
        LLMService llmService = mock(LLMService.class);
        AgentRuntimeContextLoader contextLoader = mock(AgentRuntimeContextLoader.class);
        AgentMessageBridge messageBridge = mock(AgentMessageBridge.class);

        AgentRuntimeContext context = new AgentRuntimeContext(
                "agent-1",
                "Support",
                "support agent",
                "system prompt",
                "glm-4.6",
                12,
                List.of(),
                List.of(),
                "session file summary",
                "session summary",
                "",
                AgentExecutionMode.REACT
        );

        when(contextLoader.load("agent-1", "session-1", null, null, null, null)).thenReturn(context);

        ChatAgentFactory factory = new ChatAgentFactory(
                TestPromptLoader.create(), llmService, contextLoader, messageBridge,
                new AgentRunPolicyProperties()
        );

        ChatAgent chatAgent = factory.create("agent-1", "session-1");

        AgentRunContext runContext = (AgentRunContext) ReflectionTestUtils.getField(chatAgent, "runContext");
        assertThat(runContext.relevantLongTermMemories()).isEmpty();
    }
}
