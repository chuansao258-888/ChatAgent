package com.yulong.chatagent.agent;

import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 3: 验证内部决策收集行为。
 */
@ExtendWith(MockitoExtension.class)
class InternalDecisionCollectionTest {

    @Mock private SseService sseService;
    @Mock private ChatMessageFacadeService chatMessageFacadeService;
    @Mock private LLMService llmService;
    @Mock private com.yulong.chatagent.conversation.converter.ChatMessageConverter chatMessageConverter;
    @Mock private com.yulong.chatagent.agent.runtime.CurrentTurnCitationHolder currentTurnCitationHolder;
    @Mock private com.yulong.chatagent.chat.routing.ChatRoutingProperties routingProperties;

    @Captor private ArgumentCaptor<ChatMessageDTO> dtoCaptor;
    @Captor private ArgumentCaptor<SseMessage> sseCaptor;

    @Mock private ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider;

    private AgentMessageBridgeImpl bridge;

    @BeforeEach
    void setUp() {
        lenient().when(routingProperties.getStreamTotalTimeoutSeconds()).thenReturn(30);
        lenient().when(routingProperties.getFirstPacketTimeoutSeconds()).thenReturn(10);
        lenient().when(meterRegistryProvider.getIfAvailable()).thenReturn(null);
        bridge = new AgentMessageBridgeImpl(
                sseService, chatMessageConverter, chatMessageFacadeService,
                currentTurnCitationHolder, routingProperties,
                meterRegistryProvider
        );
    }

    @Test
    @DisplayName("INTERNAL_TRACE_ONLY with tool calls: persists only after explicit preflight handoff")
    void internalTraceWithToolCalls_shouldPersistAsInternal() {
        // Arrange
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("tc1", "function", "tool1", "{}");
        AssistantMessage assistant = AssistantMessage.builder()
                .content(null)
                .toolCalls(List.of(toolCall))
                .build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistant)));
        BufferedStreamingResponse buffered = new BufferedStreamingResponse(chatResponse, List.of());

        when(llmService.streamDecisionWithRouting(any(Prompt.class), eq("sys"), eq(List.of()), eq(true)))
                .thenReturn(buffered);
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenAnswer(inv -> {
                    ChatMessageDTO dto = inv.getArgument(0);
                    return CreateChatMessageResponse.builder()
                            .chatMessageId("msg-1").turnId(dto.getTurnId()).turnSeq(1L).build();
                });

        // Act
        BufferedStreamingResponse result = bridge.collectDecisionResponse(
                "session-1", "turn-1", new Prompt("test"), "sys",
                List.of(), llmService, DecisionVisibility.INTERNAL_TRACE_ONLY,
                true, "EXECUTE", "S2");

        // Assert: response returned
        assertThat(result).isSameAs(buffered);

        // Collection itself must not persist raw model calls before shared preflight.
        verifyNoInteractions(chatMessageFacadeService);

        // Simulate the runtime's explicit handoff after preflight succeeds.
        bridge.persistInternalAssistantToolCalls(
                "session-1", "turn-1", assistant, "EXECUTE", "S2");

        // Assert: persisted as internal with correct phase/stepId after the handoff.
        verify(chatMessageFacadeService).createChatMessage(dtoCaptor.capture());
        ChatMessageDTO persisted = dtoCaptor.getValue();
        assertThat(persisted.getRole()).isEqualTo(ChatMessageDTO.RoleType.ASSISTANT);
        assertThat(persisted.getMetadata().getInternal()).isTrue();
        assertThat(persisted.getMetadata().getDeepThinkPhase()).isEqualTo("EXECUTE");
        assertThat(persisted.getMetadata().getPlanStepId()).isEqualTo("S2");
        assertThat(persisted.getMetadata().getToolCalls()).containsExactly(toolCall);

        // Assert: no SSE content events published for internal decisions
        verify(sseService, never()).publish(any(), any());
    }

    @Test
    @DisplayName("INTERNAL_TRACE_ONLY ReAct tool decision: returns response without internal persistence")
    void internalReactToolDecision_shouldNotPersistInternalMessage() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("tc1", "function", "tool1", "{}");
        AssistantMessage assistant = AssistantMessage.builder()
                .content(null)
                .toolCalls(List.of(toolCall))
                .build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistant)));
        BufferedStreamingResponse buffered = new BufferedStreamingResponse(chatResponse, List.of());

        when(llmService.streamDecisionWithRouting(any(Prompt.class), eq("sys"), eq(List.of()), eq(false)))
                .thenReturn(buffered);

        BufferedStreamingResponse result = bridge.collectDecisionResponse(
                "session-1", "turn-1", new Prompt("test"), "sys",
                List.of(), llmService, DecisionVisibility.INTERNAL_TRACE_ONLY,
                false, null, null);

        assertThat(result).isSameAs(buffered);
        verify(chatMessageFacadeService, never()).createChatMessage(any(ChatMessageDTO.class));
        verify(sseService, never()).publish(any(), any());
    }

    @Test
    @DisplayName("INTERNAL_TRACE_ONLY without tool calls: no message persisted, returns BufferedStreamingResponse")
    void internalTraceNoToolCalls_shouldNotPersist() {
        // Arrange
        AssistantMessage assistant = AssistantMessage.builder()
                .content("plan result JSON")
                .toolCalls(List.of())
                .build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistant)));
        BufferedStreamingResponse buffered = new BufferedStreamingResponse(chatResponse, List.of());

        when(llmService.streamDecisionWithRouting(any(Prompt.class), eq("sys"), eq(List.of()), eq(true)))
                .thenReturn(buffered);

        // Act
        BufferedStreamingResponse result = bridge.collectDecisionResponse(
                "session-1", "turn-1", new Prompt("test"), "sys",
                List.of(), llmService, DecisionVisibility.INTERNAL_TRACE_ONLY,
                true, "PLAN", null);

        // Assert: response returned with content
        assertThat(result.response().getResult().getOutput().getText()).isEqualTo("plan result JSON");

        // Assert: no messages persisted
        verify(chatMessageFacadeService, never()).createChatMessage(any(ChatMessageDTO.class));
        verify(sseService, never()).publish(any(), any());
    }

    @Test
    @DisplayName("USER_VISIBLE_PROVISIONAL delegates to streamDecisionResponse")
    void userVisible_delegatesToStreamDecisionResponse() {
        // Arrange: set up the full streamDecisionResponse mock chain
        AssistantMessage assistant = AssistantMessage.builder()
                .content("final answer")
                .toolCalls(List.of())
                .build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistant)));
        BufferedStreamingResponse buffered = new BufferedStreamingResponse(chatResponse, List.of());

        // streamDecisionResponse calls the old 4-arg overload (Prompt, String, List, StreamCallback)
        // which is now a default method delegating to the 5-arg version.
        // Use lenient() to avoid strict stubbing mismatch with the default method dispatch.
        lenient().when(llmService.streamDecisionWithRouting(any(Prompt.class), eq("sys"), eq(List.of()),
                any(com.yulong.chatagent.chat.routing.StreamCallback.class)))
                .thenReturn(buffered);
        when(currentTurnCitationHolder.peek(eq("session-1"), eq("turn-1"))).thenReturn(List.of());
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenAnswer(inv -> {
                    ChatMessageDTO dto = inv.getArgument(0);
                    return CreateChatMessageResponse.builder()
                            .chatMessageId("msg-1").turnId(dto.getTurnId()).turnSeq(1L).build();
                });
        when(chatMessageConverter.toVO(any(ChatMessageDTO.class)))
                .thenAnswer(inv -> {
                    ChatMessageDTO dto = inv.getArgument(0);
                    return ChatMessageVO.builder()
                            .id(dto.getId()).sessionId(dto.getSessionId())
                            .turnId(dto.getTurnId()).role(dto.getRole())
                            .content(dto.getContent()).build();
                });

        // Act
        BufferedStreamingResponse result = bridge.collectDecisionResponse(
                "session-1", "turn-1", new Prompt("test"), "sys",
                List.of(), llmService, DecisionVisibility.USER_VISIBLE_PROVISIONAL,
                false, null, null);

        // Assert: returns the same response
        assertThat(result).isSameAs(buffered);

        // Assert: created a provisional message (streamDecisionResponse behavior)
        verify(chatMessageFacadeService).createChatMessage(any(ChatMessageDTO.class));
    }

    @Test
    @DisplayName("publishStatusEvent emits correct SSE with null metadata")
    void publishStatusEvent_shouldEmitStatusOnlySse() {
        // Act
        bridge.publishStatusEvent("session-1", "turn-1", SseMessage.Type.AI_PLANNING, "正在规划...");

        // Assert
        verify(sseService).publish(eq("session-1"), sseCaptor.capture());
        SseMessage sse = sseCaptor.getValue();
        assertThat(sse.getType()).isEqualTo(SseMessage.Type.AI_PLANNING);
        assertThat(sse.getPayload().getStatusText()).isEqualTo("正在规划...");
        assertThat(sse.getPayload().getTurnId()).isEqualTo("turn-1");
        assertThat(sse.getPayload().getMessage()).isNull();
        assertThat(sse.getMetadata()).isNull();
    }

    @Test
    @DisplayName("publishStatusEvent for AI_EXECUTING with step progress")
    void publishStatusEvent_executingWithProgress() {
        // Act
        bridge.publishStatusEvent("session-1", "turn-1", SseMessage.Type.AI_EXECUTING, "正在执行 S2/5...");

        // Assert
        verify(sseService).publish(eq("session-1"), sseCaptor.capture());
        SseMessage sse = sseCaptor.getValue();
        assertThat(sse.getType()).isEqualTo(SseMessage.Type.AI_EXECUTING);
        assertThat(sse.getPayload().getStatusText()).isEqualTo("正在执行 S2/5...");
        assertThat(sse.getMetadata()).isNull();
    }

    @Test
    @DisplayName("ChatMessageDTO.MetaData carries internal fields correctly")
    void metaData_shouldCarryInternalFields() {
        ChatMessageDTO internalMsg = ChatMessageDTO.builder()
                .id("int-1")
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(null)
                .metadata(ChatMessageDTO.MetaData.builder()
                        .internal(true)
                        .deepThinkPhase("EXECUTE")
                        .planStepId("S1")
                        .build())
                .build();
        ChatMessageDTO normalMsg = ChatMessageDTO.builder()
                .id("norm-1")
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content("hello")
                .metadata(ChatMessageDTO.MetaData.builder().build())
                .build();
        ChatMessageDTO noMetadata = ChatMessageDTO.builder()
                .id("nometa-1")
                .role(ChatMessageDTO.RoleType.USER)
                .content("hi")
                .build();

        // Verify internal flag detection
        assertThat(internalMsg.getMetadata().getInternal()).isTrue();
        assertThat(internalMsg.getMetadata().getDeepThinkPhase()).isEqualTo("EXECUTE");
        assertThat(internalMsg.getMetadata().getPlanStepId()).isEqualTo("S1");
        assertThat(normalMsg.getMetadata().getInternal()).isNull();
        assertThat(noMetadata.getMetadata()).isNull();
    }
}
