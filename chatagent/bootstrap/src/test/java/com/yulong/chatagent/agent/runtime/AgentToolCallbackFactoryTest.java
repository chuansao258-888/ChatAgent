package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.admin.application.ToolFacadeService;
import com.yulong.chatagent.agent.tools.Tool;
import com.yulong.chatagent.agent.tools.ToolType;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.support.dto.AgentDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

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

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(toolFacadeService);
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
}
