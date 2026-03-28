package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.event.ConversationTurnCompletedEvent;
import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationTurnCompletionPublisherTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private ConversationTurnCompletionPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new ConversationTurnCompletionPublisher(chatMessageRepository, applicationEventPublisher);
    }

    @Test
    void shouldPublishCompletedTurnEventWithSeqAnchor() {
        when(chatMessageRepository.findMaxSeqNoBySessionId("session-1")).thenReturn(12L);

        boolean published = publisher.publishCompletedTurn("session-1", "turn-1");

        assertThat(published).isTrue();
        ArgumentCaptor<ConversationTurnCompletedEvent> captor = ArgumentCaptor.forClass(ConversationTurnCompletedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo("session-1");
        assertThat(captor.getValue().turnId()).isEqualTo("turn-1");
        assertThat(captor.getValue().lastSeqNo()).isEqualTo(12L);
    }

    @Test
    void shouldSkipPublishingWhenAnchorIsMissing() {
        when(chatMessageRepository.findMaxSeqNoBySessionId("session-1")).thenReturn(null);

        boolean published = publisher.publishCompletedTurn("session-1", "turn-1");

        assertThat(published).isFalse();
        verify(applicationEventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }
}
