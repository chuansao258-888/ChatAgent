package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.AgentExecutionModeResolver;
import com.yulong.chatagent.agent.runtime.AgentRunPolicyProperties;
import com.yulong.chatagent.agent.runtime.AgentSessionFileSummaryResolver;
import com.yulong.chatagent.conversation.event.ChatEventDispatcher;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.conversation.model.request.CreateChatMessageRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.vo.ChatSessionVO;
import com.yulong.chatagent.conversation.summary.ConversationTurnCompletionPublisher;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.intent.application.ConversationTurnPreparationService;
import com.yulong.chatagent.intent.application.IntentPolicyProperties;
import com.yulong.chatagent.intent.application.IntentUnderstandingRequest;
import com.yulong.chatagent.intent.application.TurnPreparationContext;
import com.yulong.chatagent.intent.application.TurnPreparationContextAssembler;
import com.yulong.chatagent.intent.application.TurnPreparationResult;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationOrchestratorServiceTest {

    private final ConversationOrchestratorService subject = new ConversationOrchestratorService(
            mock(ChatSessionFacadeService.class),
            mock(ChatSessionRepository.class),
            mock(ChatMessageFacadeService.class),
            mock(ConversationTurnPreparationService.class),
            mock(ChatEventDispatcher.class),
            mock(ConversationTurnCompletionPublisher.class),
            mock(SseService.class),
            new AgentExecutionModeResolver(new AgentRunPolicyProperties()),
            mock(AgentSessionFileSummaryResolver.class),
            mock(TurnPreparationContextAssembler.class)
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

    @Test
    void shouldReuseLoadedHistoryForSanitizedTurnPreparation() {
        ChatSessionFacadeService sessionService = mock(ChatSessionFacadeService.class);
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        ConversationTurnPreparationService preparationService = mock(ConversationTurnPreparationService.class);
        ChatEventDispatcher dispatcher = mock(ChatEventDispatcher.class);
        AgentSessionFileSummaryResolver assetResolver = mock(AgentSessionFileSummaryResolver.class);
        TurnPreparationContextAssembler assembler = new TurnPreparationContextAssembler(new IntentPolicyProperties());
        ConversationOrchestratorService service = new ConversationOrchestratorService(
                sessionService, sessionRepository, messageService, preparationService, dispatcher,
                mock(ConversationTurnCompletionPublisher.class), mock(SseService.class),
                new AgentExecutionModeResolver(new AgentRunPolicyProperties()), assetResolver, assembler);
        String turnId = "11111111-1111-1111-1111-111111111111";
        when(sessionService.getChatSession("session-1")).thenReturn(
                ChatSessionVO.builder().id("session-1").agentId("agent-1").build());
        when(sessionRepository.allocateNextTurnSeq("session-1")).thenReturn(2L);
        when(messageService.createChatMessage(any(CreateChatMessageRequest.class))).thenReturn(
                CreateChatMessageResponse.builder().chatMessageId("current").turnId(turnId).turnSeq(2L).build());
        when(messageService.getChatMessagesBySessionIdRecently("session-1", 12)).thenReturn(List.of(
                message("u1", "old-turn", 1L, ChatMessageDTO.RoleType.USER,
                        "visible question", false, true),
                message("a1", "old-turn", 2L, ChatMessageDTO.RoleType.ASSISTANT,
                        "visible answer", false, true),
                message("trace", "old-turn", 3L, ChatMessageDTO.RoleType.ASSISTANT,
                        "private trace", true, true),
                message("current", turnId, 4L, ChatMessageDTO.RoleType.USER,
                        "what about it?", false, false)));
        when(assetResolver.resolveForSession("session-1")).thenReturn("PDF");
        when(preparationService.prepare(any(TurnPreparationContext.class)))
                .thenReturn(TurnPreparationResult.passthrough());
        when(sessionRepository.findById("session-1"))
                .thenReturn(ChatSessionDTO.builder().id("session-1").userId("user-1").build());

        service.handleUserTurn(CreateChatMessageRequest.builder()
                .sessionId("session-1").turnId(turnId).role(ChatMessageDTO.RoleType.USER)
                .content("what about it?").executionMode(AgentExecutionMode.REACT).build());

        ArgumentCaptor<TurnPreparationContext> contextCaptor =
                ArgumentCaptor.forClass(TurnPreparationContext.class);
        verify(preparationService).prepare(contextCaptor.capture());
        assertThat(contextCaptor.getValue().recentVisibleTurns())
                .extracting(IntentUnderstandingRequest.RecentTurn::text)
                .containsExactly("visible question", "visible answer")
                .doesNotContain("private trace", "what about it?");
        assertThat(contextCaptor.getValue().sessionAssetSummary()).isEqualTo("PDF");
        verify(messageService, times(1)).getChatMessagesBySessionIdRecently("session-1", 12);
        verify(assetResolver, times(1)).resolveForSession("session-1");
    }

    private ChatMessageDTO message(String id, String turnId, Long seq,
                                   ChatMessageDTO.RoleType role, String content,
                                   boolean internal, boolean completed) {
        return ChatMessageDTO.builder()
                .id(id).turnId(turnId).seqNo(seq).role(role).content(content)
                .turnCompleted(completed)
                .metadata(ChatMessageDTO.MetaData.builder().internal(internal).build())
                .build();
    }
}
