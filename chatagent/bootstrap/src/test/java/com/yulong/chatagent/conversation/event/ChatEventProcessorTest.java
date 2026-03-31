package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.agent.ChatAgent;
import com.yulong.chatagent.agent.ChatAgentFactory;
import com.yulong.chatagent.agent.runtime.CurrentIntentResolutionHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnCitationHolder;
import com.yulong.chatagent.chat.ChatModelAvailability;
import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.converter.ChatMessageConverter;
import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;
import com.yulong.chatagent.conversation.summary.ConversationTurnCompletionPublisher;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
        verify(sseService).send(anyString(), any(SseMessage.class));
        verify(conversationTurnCompletionPublisher).publishCompletedTurn("session-1", "turn-1");
        verify(chatAgentFactory, never()).create(anyString(), anyString(), anyString(), any(), anyString());
        verify(currentTurnCitationHolder).clear("session-1", "turn-1");
    }

    @Test
    void shouldClearContextWhenAgentProcessingFailsAndStillAllowFallbackPublishing() {
        ChatEventProcessor processor = newProcessor();
        ChatEvent event = sampleEvent();
        when(chatModelAvailability.hasConfiguredProvider()).thenReturn(true);
        when(chatAgentFactory.create("agent-1", "session-1", "turn-1", event.getIntentResolution(), "rewritten"))
                .thenReturn(chatAgent);
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("assistant-1").build());
        when(chatMessageConverter.toVO(any(ChatMessageDTO.class)))
                .thenReturn(ChatMessageVO.builder().id("assistant-1").sessionId("session-1").turnId("turn-1").build());
        doThrow(new RuntimeException("boom")).when(chatAgent).run();

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(RuntimeException.class);

        assertThat(CurrentIntentResolutionHolder.get()).isNull();
        verify(currentTurnCitationHolder).clear("session-1", "turn-1");

        processor.publishFailure(event, new RuntimeException("boom"));

        verify(chatMessageFacadeService).createChatMessage(any(ChatMessageDTO.class));
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
                currentTurnCitationHolder
        );
    }

    private ChatEvent sampleEvent() {
        IntentResolution resolution = new IntentResolution(null, null, null, null, null, null);
        return new ChatEvent("agent-1", "session-1", "turn-1", "msg-1", "hello", 3, resolution, "rewritten");
    }
}
