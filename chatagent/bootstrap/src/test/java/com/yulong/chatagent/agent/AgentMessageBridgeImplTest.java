package com.yulong.chatagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.runtime.CurrentTurnCitationHolder;
import com.yulong.chatagent.chat.routing.ChatRoutingProperties;
import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.converter.ChatMessageConverter;
import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.rag.model.CitationMetadata;
import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentMessageBridgeImplTest {

    @Mock
    private SseService sseService;

    @Mock
    private ChatMessageFacadeService chatMessageFacadeService;

    private CurrentTurnCitationHolder currentTurnCitationHolder;
    private AgentMessageBridgeImpl agentMessageBridge;

    @BeforeEach
    void setUp() {
        currentTurnCitationHolder = new CurrentTurnCitationHolder();
        agentMessageBridge = new AgentMessageBridgeImpl(
                sseService,
                new ChatMessageConverter(new ObjectMapper()),
                chatMessageFacadeService,
                currentTurnCitationHolder,
                new ChatRoutingProperties()
        );
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("msg-1").build());
    }

    @Test
    void shouldAttachCitationsToFinalAssistantMessageAndClearHolder() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(citation("doc-1")));
        AssistantMessage assistantMessage = org.mockito.Mockito.mock(AssistantMessage.class);
        when(assistantMessage.getText()).thenReturn("Final answer [1]");
        when(assistantMessage.getToolCalls()).thenReturn(List.of());

        agentMessageBridge.persistAndPublish("session-1", "turn-1", assistantMessage);

        ArgumentCaptor<ChatMessageDTO> dtoCaptor = ArgumentCaptor.forClass(ChatMessageDTO.class);
        verify(chatMessageFacadeService).createChatMessage(dtoCaptor.capture());
        assertThat(dtoCaptor.getValue().getMetadata().getCitations()).hasSize(1);
        assertThat(dtoCaptor.getValue().getMetadata().getCitations().get(0).documentId()).isEqualTo("doc-1");
        assertThat(currentTurnCitationHolder.peek("session-1", "turn-1")).isEmpty();

        ArgumentCaptor<SseMessage> sseCaptor = ArgumentCaptor.forClass(SseMessage.class);
        verify(sseService).publish(org.mockito.ArgumentMatchers.eq("session-1"), sseCaptor.capture());
        assertThat(sseCaptor.getValue().getPayload().getMessage().getMetadata().getCitations()).hasSize(1);
    }

    @Test
    void shouldNotAttachCitationsToIntermediateAssistantToolCallMessage() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(citation("doc-1")));
        AssistantMessage assistantMessage = org.mockito.Mockito.mock(AssistantMessage.class);
        when(assistantMessage.getText()).thenReturn("Let me search");
        when(assistantMessage.getToolCalls()).thenReturn(List.of(
                new AssistantMessage.ToolCall("tool-call-1", "function", "SessionFileSearchTool", "{\"query\":\"vacation\"}")
        ));

        agentMessageBridge.persistAndPublish("session-1", "turn-1", assistantMessage);

        ArgumentCaptor<ChatMessageDTO> dtoCaptor = ArgumentCaptor.forClass(ChatMessageDTO.class);
        verify(chatMessageFacadeService).createChatMessage(dtoCaptor.capture());
        assertThat(dtoCaptor.getValue().getMetadata().getCitations()).isNull();
        assertThat(currentTurnCitationHolder.peek("session-1", "turn-1")).hasSize(1);
    }

    private CitationMetadata citation(String documentId) {
        return new CitationMetadata(
                RagSourceType.KNOWLEDGE_BASE,
                "kb-1",
                documentId,
                "Handbook.pdf",
                "Leave Policy",
                2,
                "Employees can apply for leave in Workday.",
                0.95d,
                "reranker",
                false
        );
    }
}
