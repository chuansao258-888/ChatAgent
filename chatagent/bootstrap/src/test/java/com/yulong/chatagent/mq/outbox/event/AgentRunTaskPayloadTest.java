package com.yulong.chatagent.mq.outbox.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.contract.IntentLabel;
import com.yulong.chatagent.agent.runtime.contract.RetrievalMode;
import com.yulong.chatagent.agent.runtime.contract.RetrievalSource;
import com.yulong.chatagent.agent.runtime.contract.SourceNeed;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContractBuilder;
import com.yulong.chatagent.conversation.event.ChatEvent;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.application.ConfidenceStatus;
import com.yulong.chatagent.intent.application.IntentCandidateEvidence;
import com.yulong.chatagent.intent.application.IntentDecision;
import com.yulong.chatagent.intent.application.IntentDecisionSource;
import com.yulong.chatagent.intent.application.IntentRouteOutcome;
import com.yulong.chatagent.intent.application.IntentUnderstandingResult;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        // 老消息没有 executionContract 字段，反序列化后应为 null（向后兼容）。
        assertThat(payload.executionContract()).isNull();
        assertThat(payload.toChatEvent().getUserId()).isEmpty();
        assertThat(payload.toChatEvent().getExecutionMode()).isEqualTo(AgentExecutionMode.REACT);
        assertThat(payload.toChatEvent().getExecutionContract()).isNull();
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

    @Test
    void shouldPreserveExecutionContractAcrossMqPayloadRoundTrip() throws Exception {
        // Phase 1 warn 模式：带真实 contract 的 ChatEvent 经 MQ 序列化往返后，
        // contract 的关键字段必须保留，否则 warn 模式观测会丢数据。
        IntentResolution resolution = new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().id("leaf").name("年假").build()),
                List.of("kb-1"),
                ScopePolicy.STRICT,
                List.of(),
                null
        );
        TurnExecutionContract contract = com.yulong.chatagent.agent.runtime.contract.ContractTestSupport.contractBuilder()
                .build(resolution, "年假怎么申请？", "年假 申请 流程", AgentExecutionMode.DEEPTHINK);
        ChatEvent event = new ChatEvent(
                "agent-1",
                "session-1",
                "turn-1",
                7L,
                "msg-1",
                "年假怎么申请？",
                3,
                resolution,
                "年假 申请 流程",
                "user-1",
                AgentExecutionMode.DEEPTHINK,
                contract
        );

        AgentRunTaskPayload payload = AgentRunTaskPayload.fromChatEvent(event);
        String json = objectMapper.writeValueAsString(payload);
        AgentRunTaskPayload restoredPayload = objectMapper.readValue(json, AgentRunTaskPayload.class);

        assertThat(restoredPayload.executionContract()).isNotNull();
        TurnExecutionContract restored = restoredPayload.executionContract();
        assertThat(restored.version()).isEqualTo(TurnExecutionContract.VERSION);
        assertThat(restored.analysis().primaryIntent()).isEqualTo(IntentLabel.KB_QA);
        assertThat(restored.analysis().sourceNeed()).isEqualTo(SourceNeed.KB);
        assertThat(restored.retrieval().mode()).isEqualTo(RetrievalMode.REQUIRED_BEFORE_ANSWER);
        assertThat(restored.retrieval().source()).isEqualTo(RetrievalSource.INTENT_KB);
        assertThat(restored.intent().kind()).isEqualTo(IntentKind.KB);
        assertThat(restored.executionMode()).isEqualTo(AgentExecutionMode.DEEPTHINK);

        // restored ChatEvent 也必须保留 contract（consumer 侧从 payload 恢复）。
        ChatEvent restoredEvent = restoredPayload.toChatEvent();
        assertThat(restoredEvent.getExecutionContract()).isNotNull();
        assertThat(restoredEvent.getExecutionContract().analysis().sourceNeed()).isEqualTo(SourceNeed.KB);
        assertThat(restoredEvent.getExecutionContract().retrieval().mode()).isEqualTo(RetrievalMode.REQUIRED_BEFORE_ANSWER);
    }

    @Test
    void shouldPreserveTypedIntentDecisionAcrossMqPayloadRoundTrip() throws Exception {
        IntentResolution resolution = new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().id("leave").name("Annual Leave").build()),
                List.of("kb-1"), ScopePolicy.STRICT, List.of(), null);
        IntentDecision decision = new IntentDecision(
                IntentRouteOutcome.KNOWN_INTENT, "leave", List.of(),
                List.of(new IntentCandidateEvidence(
                        "leave", "HR > Annual Leave", 1.7d, 1.1d, 1,
                        List.of("semantic_match"))),
                List.of(), IntentDecisionSource.CLASSIFIER, 0.94d,
                ConfidenceStatus.CALIBRATED, "v1", List.of("classifier_schema_valid"));
        IntentUnderstandingResult understanding = new IntentUnderstandingResult(
                decision, SourceNeed.KB,
                com.yulong.chatagent.agent.runtime.contract.TimeSensitivity.STATIC,
                com.yulong.chatagent.agent.runtime.contract.ActionRisk.READ_ONLY,
                List.of(), false, false);
        TurnExecutionContract contract = com.yulong.chatagent.agent.runtime.contract.ContractTestSupport
                .contractBuilder().build(resolution, "annual leave process", "annual leave process",
                        AgentExecutionMode.REACT, understanding);
        ChatEvent event = new ChatEvent(
                "agent-1", "session-1", "turn-1", 8L, "msg-1",
                "annual leave process", 3, resolution, "annual leave process", "user-1",
                AgentExecutionMode.REACT, contract);

        AgentRunTaskPayload restored = objectMapper.readValue(
                objectMapper.writeValueAsString(AgentRunTaskPayload.fromChatEvent(event)),
                AgentRunTaskPayload.class);

        assertThat(restored.executionContract().analysis().intentDecision()).isEqualTo(decision);
        assertThat(restored.toChatEvent().getExecutionContract().analysis().intentDecision().primaryNodeId())
                .isEqualTo("leave");
        assertThat(restored.toChatEvent().getExecutionContract().analysis().confidence())
                .isEqualTo(0.94d);
    }
}
