package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.agent.AgentRunException;
import com.yulong.chatagent.agent.AgentRunResult;
import com.yulong.chatagent.agent.ChatAgent;
import com.yulong.chatagent.agent.ChatAgentFactory;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.CurrentIntentResolutionHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnCitationHolder;
import com.yulong.chatagent.support.chat.ChatModelAvailability;
import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.converter.ChatMessageConverter;
import com.yulong.chatagent.conversation.metrics.ChatTurnMetricRecorder;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;
import com.yulong.chatagent.conversation.summary.ConversationTurnCompletionPublisher;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatEventProcessorTest {

    @Mock
    private ChatAgentFactory chatAgentFactory;

    @Mock
    private ChatModelAvailability chatModelAvailability;

    @Mock
    private ChatMessageFacadeService chatMessageFacadeService;

    @Mock
    private ChatMessageConverter chatMessageConverter;

    @Mock
    private ConversationTurnCompletionPublisher conversationTurnCompletionPublisher;

    @Mock
    private SseService sseService;

    @Mock
    private CurrentTurnCitationHolder currentTurnCitationHolder;

    @Mock
    private ChatTurnMetricRecorder chatTurnMetricRecorder;

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatAgent chatAgent;

    @AfterEach
    void clearContext() {
        CurrentIntentResolutionHolder.clear();
    }

    @Test
    void shouldPersistFallbackAndCompleteTurnWhenNoModelIsConfigured() {
        ChatEventProcessor processor = newProcessor();
        when(chatModelAvailability.hasConfiguredProvider()).thenReturn(false);
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("assistant-1").build());
        when(chatMessageConverter.toVO(any(ChatMessageDTO.class)))
                .thenReturn(ChatMessageVO.builder().id("assistant-1").sessionId("session-1").turnId("turn-1").build());

        processor.process(sampleEvent());

        ArgumentCaptor<ChatMessageDTO> messageCaptor = ArgumentCaptor.forClass(ChatMessageDTO.class);
        verify(chatMessageFacadeService).createChatMessage(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getContent()).contains("ChatAgent is running without a configured chat model");
        verify(sseService, times(3)).publish(anyString(), any(SseMessage.class));
        verify(conversationTurnCompletionPublisher).publishCompletedTurn("session-1", "turn-1");
        verify(chatAgentFactory, never()).create(anyString(), anyString(), anyString(), any(), anyString(), anyString(), any());
        verify(chatTurnMetricRecorder).record(any(ChatEvent.class), any(AgentRunResult.class));
        verify(currentTurnCitationHolder).clear("session-1", "turn-1");
    }

    @Test
    void shouldClearContextWhenAgentProcessingFailsAndStillAllowFallbackPublishing() {
        ChatEventProcessor processor = newProcessor();
        ChatEvent event = new ChatEvent("agent-1", "session-1", "turn-1", "msg-1", "hello", 3, null, "rewritten", null);
        when(chatModelAvailability.hasConfiguredProvider()).thenReturn(true);
        when(chatSessionRepository.findById("session-1")).thenReturn(ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(chatAgentFactory.create("agent-1", "session-1", "turn-1", event.getIntentResolution(), "rewritten", "user-1", AgentExecutionMode.REACT))
                .thenReturn(chatAgent);
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("assistant-1").build());
        when(chatMessageConverter.toVO(any(ChatMessageDTO.class)))
                .thenReturn(ChatMessageVO.builder().id("assistant-1").sessionId("session-1").turnId("turn-1").build());
        AgentRunException runException = new AgentRunException(
                "boom",
                new RuntimeException("boom"),
                AgentRunResult.failure(123L, false, new RuntimeException("boom"))
        );
        doThrow(runException).when(chatAgent).run();

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(AgentRunException.class);

        assertThat(CurrentIntentResolutionHolder.get()).isNull();
        verify(currentTurnCitationHolder).clear("session-1", "turn-1");

        processor.publishFailure(event, runException);

        verify(chatMessageFacadeService).deleteAssistantAndToolMessagesForTurn("session-1", "turn-1");
        ArgumentCaptor<SseMessage> sseCaptor = ArgumentCaptor.forClass(SseMessage.class);
        verify(sseService, times(4)).publish(anyString(), sseCaptor.capture());
        assertThat(sseCaptor.getAllValues())
                .extracting(SseMessage::getType)
                .containsExactly(
                        SseMessage.Type.TURN_ROLLBACK,
                        SseMessage.Type.AI_GENERATED_CONTENT,
                        SseMessage.Type.AI_ERROR,
                        SseMessage.Type.AI_DONE
                );
        InOrder inOrder = inOrder(sseService, conversationTurnCompletionPublisher);
        inOrder.verify(sseService, times(4)).publish(anyString(), any(SseMessage.class));
        inOrder.verify(conversationTurnCompletionPublisher).publishCompletedTurn("session-1", "turn-1");
        verify(chatTurnMetricRecorder).record(event, runException.getResult());
    }

    @Test
    void shouldTriggerRollbackAndNotifyFrontend() {
        ChatEventProcessor processor = newProcessor();

        processor.rollbackTurn("session-1", "turn-1");

        verify(chatMessageFacadeService).deleteAssistantAndToolMessagesForTurn("session-1", "turn-1");
        ArgumentCaptor<SseMessage> sseCaptor = ArgumentCaptor.forClass(SseMessage.class);
        verify(sseService).publish(anyString(), sseCaptor.capture());
        assertThat(sseCaptor.getValue().getType()).isEqualTo(SseMessage.Type.TURN_ROLLBACK);
        assertThat(sseCaptor.getValue().getPayload().getTurnId()).isEqualTo("turn-1");
    }

    @Test
    void shouldPassDeepThinkModeToAgentFactory() {
        ChatEventProcessor processor = newProcessor();
        IntentResolution resolution = new IntentResolution(null, null, null, null, null, null);
        ChatEvent event = new ChatEvent(
                "agent-1",
                "session-1",
                "turn-1",
                1L,
                "msg-1",
                "hello",
                3,
                resolution,
                "rewritten",
                "user-1",
                AgentExecutionMode.DEEPTHINK
        );
        AgentRunResult runResult = AgentRunResult.success(42L, true);

        when(chatModelAvailability.hasConfiguredProvider()).thenReturn(true);
        when(chatAgentFactory.create("agent-1", "session-1", "turn-1", resolution, "rewritten", "user-1", AgentExecutionMode.DEEPTHINK))
                .thenReturn(chatAgent);
        when(chatAgent.run()).thenReturn(runResult);

        processor.process(event);

        verify(chatAgentFactory).create("agent-1", "session-1", "turn-1", resolution, "rewritten", "user-1", AgentExecutionMode.DEEPTHINK);
        verify(chatTurnMetricRecorder).record(event, runResult);
        verify(conversationTurnCompletionPublisher).publishCompletedTurn("session-1", "turn-1");
    }

    private ChatEventProcessor newProcessor() {
        return new ChatEventProcessor(
                chatAgentFactory,
                chatModelAvailability,
                chatMessageFacadeService,
                chatMessageConverter,
                conversationTurnCompletionPublisher,
                sseService,
                currentTurnCitationHolder,
                chatTurnMetricRecorder,
                chatSessionRepository
        );
    }

    private ChatEvent sampleEvent() {
        IntentResolution resolution = new IntentResolution(null, null, null, null, null, null);
        return new ChatEvent("agent-1", "session-1", "turn-1", "msg-1", "hello", 3, resolution, "rewritten", "user-1");
    }
}
