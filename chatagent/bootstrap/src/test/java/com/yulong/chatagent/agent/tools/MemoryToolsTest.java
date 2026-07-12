package com.yulong.chatagent.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.memory.application.MemoryApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MemoryToolsTest {
    private final MemoryApplicationService service = mock(MemoryApplicationService.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void descriptorsAreFixedAndContextIsRequired() {
        MemoryInspectTool inspect = new MemoryInspectTool(service, json);
        MemoryCorrectTool correct = new MemoryCorrectTool(service, json);
        assertThat(inspect.getType()).isEqualTo(ToolType.FIXED);
        assertThat(inspect.effectClass()).isEqualTo(ToolEffectClass.READ_ONLY);
        assertThat(inspect.deadlineMode()).isEqualTo(DeadlineMode.ENFORCED);
        assertThat(correct.getType()).isEqualTo(ToolType.FIXED);
        assertThat(correct.effectClass()).isEqualTo(ToolEffectClass.IDEMPOTENT);
        assertThat(correct.deadlineMode()).isEqualTo(DeadlineMode.ENFORCED);
        assertThat(correct.requiresConfirmation()).isFalse();
        assertThat(correct.getToolCallbacks().get(0).call("{}", new ToolContext(Map.of())))
                .contains("CONTEXT_MISSING");
        verifyNoInteractions(service);
    }

    @Test
    void correctToolUsesOnlyServerIdentityAndRawInput() {
        LocalDateTime expected = LocalDateTime.of(2026, 1, 1, 1, 1);
        when(service.correctFromConversation(anyString(), anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(new MemoryApplicationService.CorrectResult(MemoryApplicationService.CorrectStatus.UPDATED, null));
        MemoryCorrectTool tool = new MemoryCorrectTool(service, json);
        String result = tool.getToolCallbacks().get(0).call("{\"memoryId\":\"m1\",\"expectedUpdatedAt\":\"2026-01-01T01:01:00\",\"newContent\":\"green\",\"evidenceQuote\":\"change it to green\"}",
                new ToolContext(Map.of("userId", "trusted-user", "sessionId", "s", "turnId", "t", "currentUserInput", "change it to green")));
        assertThat(result).contains("UPDATED");
        verify(service).correctFromConversation("trusted-user", "change it to green", "change it to green", "m1", expected, null, "green");
    }
}
