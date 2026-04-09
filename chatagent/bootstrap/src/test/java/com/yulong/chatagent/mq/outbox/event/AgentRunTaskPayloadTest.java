package com.yulong.chatagent.mq.outbox.event;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        assertThat(payload.forceRollback()).isFalse();
        assertThat(payload.toChatEvent().getUserId()).isEmpty();
    }
}
