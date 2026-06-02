package com.yulong.chatagent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.AgentExecutionModeResolver;
import com.yulong.chatagent.agent.runtime.AgentRunPolicyProperties;
import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.application.ChatSessionFacadeService;
import com.yulong.chatagent.conversation.application.ConversationOrchestratorService;
import com.yulong.chatagent.conversation.event.ChatEvent;
import com.yulong.chatagent.conversation.event.ChatEventDispatcher;
import com.yulong.chatagent.conversation.model.request.CreateChatMessageRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.vo.ChatSessionVO;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.conversation.summary.ConversationTurnCompletionPublisher;
import com.yulong.chatagent.intent.application.ConversationTurnPreparationService;
import com.yulong.chatagent.intent.application.TurnPreparationResult;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationExecutionModeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldDeserializeExecutionModeCaseInsensitively() {
        assertThat(AgentExecutionMode.fromJson("react")).isEqualTo(AgentExecutionMode.REACT);
        assertThat(AgentExecutionMode.fromJson("ReAct")).isEqualTo(AgentExecutionMode.REACT);
        assertThat(AgentExecutionMode.fromJson("deepthink")).isEqualTo(AgentExecutionMode.DEEPTHINK);
        assertThat(AgentExecutionMode.fromJson(" DEEPTHINK ")).isEqualTo(AgentExecutionMode.DEEPTHINK);
        assertThat(AgentExecutionMode.fromJson(null)).isNull();
        assertThat(AgentExecutionMode.fromJson(" ")).isNull();
    }

    @Test
    void shouldResolveMissingAndInvalidExecutionModeToReact() throws Exception {
        AgentRunPolicyProperties properties = new AgentRunPolicyProperties();
        AgentExecutionModeResolver resolver = new AgentExecutionModeResolver(properties);

        assertThat(resolver.resolve(null)).isEqualTo(AgentExecutionMode.REACT);

        CreateChatMessageRequest request = objectMapper.readValue("""
                {
                  "sessionId": "session-1",
                  "role": "user",
                  "content": "hello",
                  "executionMode": "bad-mode"
                }
                """, CreateChatMessageRequest.class);

        assertThat(resolver.resolve(request.getExecutionMode())).isEqualTo(AgentExecutionMode.REACT);
    }

    @Test
    void shouldKeepDeepThinkWhenFeatureFlagIsEnabled() {
        AgentRunPolicyProperties properties = new AgentRunPolicyProperties();
        properties.getDeepthink().setEnabled(true);
        AgentExecutionModeResolver resolver = new AgentExecutionModeResolver(properties);

        assertThat(resolver.resolve(AgentExecutionMode.DEEPTHINK)).isEqualTo(AgentExecutionMode.DEEPTHINK);
    }

    @Test
    void shouldDowngradeDeepThinkWhenFeatureFlagIsDisabled() {
        AgentRunPolicyProperties properties = new AgentRunPolicyProperties();
        properties.getDeepthink().setEnabled(false);
        AgentExecutionModeResolver resolver = new AgentExecutionModeResolver(properties);

        assertThat(resolver.resolve(AgentExecutionMode.DEEPTHINK)).isEqualTo(AgentExecutionMode.REACT);
    }

    @Test
    void shouldDowngradeDeepThinkWhenPolicyPropertiesAreUnavailable() {
        assertThat(AgentExecutionModeResolver.resolve(AgentExecutionMode.DEEPTHINK, null))
                .isEqualTo(AgentExecutionMode.REACT);
    }

    @Test
    void shouldPersistAndDispatchResolvedExecutionMode() {
        ChatSessionFacadeService chatSessionFacadeService = mock(ChatSessionFacadeService.class);
        ChatSessionRepository chatSessionRepository = mock(ChatSessionRepository.class);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        ConversationTurnPreparationService preparationService = mock(ConversationTurnPreparationService.class);
        ChatEventDispatcher chatEventDispatcher = mock(ChatEventDispatcher.class);

        ConversationOrchestratorService subject = new ConversationOrchestratorService(
                chatSessionFacadeService,
                chatSessionRepository,
                chatMessageFacadeService,
                preparationService,
                chatEventDispatcher,
                mock(ConversationTurnCompletionPublisher.class),
                mock(SseService.class),
                new AgentExecutionModeResolver(new AgentRunPolicyProperties())
        );

        String turnId = "11111111-1111-1111-1111-111111111111";
        when(chatSessionFacadeService.getChatSession("session-1")).thenReturn(ChatSessionVO.builder()
                .id("session-1")
                .agentId("agent-1")
                .build());
        when(chatSessionRepository.allocateNextTurnSeq("session-1")).thenReturn(1L);
        when(chatMessageFacadeService.createChatMessage(any(CreateChatMessageRequest.class)))
                .thenReturn(CreateChatMessageResponse.builder()
                        .chatMessageId("msg-1")
                        .turnId(turnId)
                        .turnSeq(1L)
                        .build());
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 12))
                .thenReturn(List.of(ChatMessageDTO.builder()
                        .id("msg-1")
                        .sessionId("session-1")
                        .turnId(turnId)
                        .role(ChatMessageDTO.RoleType.USER)
                        .content("hello")
                        .build()));
        when(preparationService.prepare("agent-1", "session-1", "hello"))
                .thenReturn(TurnPreparationResult.passthrough());
        when(chatSessionRepository.findById("session-1"))
                .thenReturn(ChatSessionDTO.builder().id("session-1").userId("user-1").build());

        subject.handleUserTurn(CreateChatMessageRequest.builder()
                .sessionId("session-1")
                .turnId(turnId)
                .role(ChatMessageDTO.RoleType.USER)
                .content("hello")
                .executionMode(AgentExecutionMode.DEEPTHINK)
                .build());

        ArgumentCaptor<CreateChatMessageRequest> requestCaptor = ArgumentCaptor.forClass(CreateChatMessageRequest.class);
        verify(chatMessageFacadeService).createChatMessage(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getExecutionMode()).isEqualTo(AgentExecutionMode.DEEPTHINK);
        assertThat(requestCaptor.getValue().getMetadata().getExecutionMode()).isEqualTo(AgentExecutionMode.DEEPTHINK);

        ArgumentCaptor<ChatEvent> eventCaptor = ArgumentCaptor.forClass(ChatEvent.class);
        verify(chatEventDispatcher).dispatch(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getExecutionMode()).isEqualTo(AgentExecutionMode.DEEPTHINK);
        assertThat(eventCaptor.getValue().getUserId()).isEqualTo("user-1");
        verify(preparationService).prepare(eq("agent-1"), eq("session-1"), eq("hello"));
    }
}
