package com.yulong.chatagent.mq.outbox.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.conversation.event.ChatEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunTaskPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldDeserializeLegacyPayloadWithoutUserId() throws Exception {
        String legacyJson = """
                {
                  "agentId": "agent-1",
                  "sessionId": "session-1",
                  "turnId": "turn-1",
                  "chatMessageId": "msg-1",
                  "userInput": "hello",
                  "recentHistorySize": 3,
                  "intentResolution": null,
                  "rewrittenInput": "rewritten",
                  "forceRollback": false
                }
                """;

        AgentRunTaskPayload payload = objectMapper.readValue(legacyJson, AgentRunTaskPayload.class);

        assertThat(payload.userId()).isEmpty();
        assertThat(payload.turnSeq()).isNull();
        assertThat(payload.executionMode()).isEqualTo(AgentExecutionMode.REACT);
        assertThat(payload.forceRollback()).isFalse();
        assertThat(payload.toChatEvent().getUserId()).isEmpty();
        assertThat(payload.toChatEvent().getExecutionMode()).isEqualTo(AgentExecutionMode.REACT);
    }

    @Test
    void shouldPreserveExecutionModeAcrossMqPayloadRoundTrip() throws Exception {
        ChatEvent event = new ChatEvent(
                "agent-1",
                "session-1",
                "turn-1",
                7L,
                "msg-1",
                "hello",
                3,
                null,
                "rewritten",
                "user-1",
                AgentExecutionMode.DEEPTHINK
        );

        AgentRunTaskPayload payload = AgentRunTaskPayload.fromChatEvent(event);
        String json = objectMapper.writeValueAsString(payload);
        AgentRunTaskPayload restoredPayload = objectMapper.readValue(json, AgentRunTaskPayload.class);

        assertThat(restoredPayload.executionMode()).isEqualTo(AgentExecutionMode.DEEPTHINK);
        assertThat(restoredPayload.toChatEvent().getExecutionMode()).isEqualTo(AgentExecutionMode.DEEPTHINK);
    }
}
