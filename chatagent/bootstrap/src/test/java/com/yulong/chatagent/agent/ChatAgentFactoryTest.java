package com.yulong.chatagent.agent;

import com.yulong.chatagent.TestPromptLoader;
import com.yulong.chatagent.chat.routing.LLMService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
                "user profile summary"
        );

        when(contextLoader.load("agent-1", "session-1", null, null)).thenReturn(context);

        ChatAgentFactory factory = new ChatAgentFactory(TestPromptLoader.create(), llmService, contextLoader, messageBridge);

        ChatAgent chatAgent = factory.create("agent-1", "session-1");

        assertThat(ReflectionTestUtils.getField(chatAgent, "agentId")).isEqualTo("agent-1");
        assertThat(ReflectionTestUtils.getField(chatAgent, "name")).isEqualTo("Support");
        assertThat(ReflectionTestUtils.getField(chatAgent, "llmService")).isSameAs(llmService);
        assertThat(ReflectionTestUtils.getField(chatAgent, "sessionFileSummary")).isEqualTo("session file summary");
        assertThat(ReflectionTestUtils.getField(chatAgent, "userProfileSummary")).isEqualTo("user profile summary");
        assertThat(ReflectionTestUtils.getField(chatAgent, "messageBridge")).isSameAs(messageBridge);
    }
}
