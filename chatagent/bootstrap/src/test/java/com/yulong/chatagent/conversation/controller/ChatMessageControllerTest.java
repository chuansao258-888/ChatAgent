package com.yulong.chatagent.conversation.controller;

import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.application.ConversationOrchestratorService;
import com.yulong.chatagent.conversation.application.SessionConcurrencyGuard;
import com.yulong.chatagent.conversation.model.request.CreateChatMessageRequest;
import com.yulong.chatagent.exception.SessionConflictException;
import com.yulong.chatagent.ratelimit.entry.EntryRateLimiter;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatMessageControllerTest {

    @Test
    void createChatMessageShouldStopAtSessionConflict() {
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        ConversationOrchestratorService conversationOrchestratorService = mock(ConversationOrchestratorService.class);
        SessionConcurrencyGuard sessionConcurrencyGuard = mock(SessionConcurrencyGuard.class);
        EntryRateLimiter entryRateLimiter = mock(EntryRateLimiter.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        ChatMessageController subject = new ChatMessageController(
                chatMessageFacadeService,
                conversationOrchestratorService,
                sessionConcurrencyGuard,
                entryRateLimiter
        );

        CreateChatMessageRequest request = CreateChatMessageRequest.builder()
                .sessionId("session-1")
                .turnId("123e4567-e89b-12d3-a456-426614174000")
                .role(ChatMessageDTO.RoleType.USER)
                .content("hello")
                .build();

        when(sessionConcurrencyGuard.acquire("session-1"))
                .thenThrow(new SessionConflictException("Another request is already starting a turn for this session"));

        assertThatThrownBy(() -> subject.createChatMessage(request, httpRequest))
                .isInstanceOf(SessionConflictException.class);

        verifyNoInteractions(conversationOrchestratorService);
    }
}
