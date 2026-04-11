package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.conversation.event.ChatEventDispatcher;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.conversation.model.request.CreateChatMessageRequest;
import com.yulong.chatagent.conversation.summary.ConversationTurnCompletionPublisher;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.intent.application.ConversationTurnPreparationService;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ConversationOrchestratorServiceTest {

    private final ConversationOrchestratorService subject = new ConversationOrchestratorService(
            mock(ChatSessionFacadeService.class),
            mock(ChatSessionRepository.class),
            mock(ChatMessageFacadeService.class),
            mock(ConversationTurnPreparationService.class),
            mock(ChatEventDispatcher.class),
            mock(ConversationTurnCompletionPublisher.class),
            mock(SseService.class)
    );

    @Test
    void handleUserTurnShouldRejectNonCanonicalTurnId() {
        CreateChatMessageRequest request = CreateChatMessageRequest.builder()
                .sessionId("session-1")
                .turnId("turn:bad")
                .role(ChatMessageDTO.RoleType.USER)
                .content("hello")
                .build();

        assertThatThrownBy(() -> subject.handleUserTurn(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("turnId must be a canonical lowercase UUID");
    }
}
