package com.yulong.chatagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.converter.ChatMessageConverter;
import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.conversation.model.request.UpdateChatMessageRequest;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.support.dto.AgentTraceMetadata;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AgentMessageBridgeImpl#attachTraceMetadata}.
 * <p>
 * Verifies the implementation logic: find the latest non-internal assistant message
 * for the given turn and update its metadata with the trace.
 */
@ExtendWith(MockitoExtension.class)
class AttachTraceMetadataTest {

    @Mock private ChatMessageFacadeService chatMessageFacadeService;
    @Mock private SseService sseService;

    private AgentTraceMetadata sampleTrace;

    @BeforeEach
    void setUp() {
        sampleTrace = AgentTraceMetadata.builder()
                .mode("DEEPTHINK")
                .planning(AgentTraceMetadata.PlanningTrace.builder()
                        .goal("test goal")
                        .stepCount(1)
                        .build())
                .build();
    }

    @Test
    void attachTraceMetadata_updatesLatestNonInternalAssistantForTurn() {
        // Simulate message list with mixed messages
        List<ChatMessageDTO> messages = List.of(
                // Internal assistant (DeepThink tool_call) — should be skipped
                dto("internal-1", "turn-1", ChatMessageDTO.RoleType.ASSISTANT,
                        ChatMessageDTO.MetaData.builder().internal(true).deepThinkPhase("EXECUTE").build()),
                // Internal tool response — should be skipped
                dto("internal-tool-1", "turn-1", ChatMessageDTO.RoleType.TOOL,
                        ChatMessageDTO.MetaData.builder().internal(true).build()),
                // Final assistant message — should be updated
                dto("final-1", "turn-1", ChatMessageDTO.RoleType.ASSISTANT,
                        ChatMessageDTO.MetaData.builder().build())
        );

        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 50))
                .thenReturn(messages);

        // Call the method under test via the interface contract
        // We create a minimal test harness that delegates to the real impl
        TestableBridge bridge = new TestableBridge(chatMessageFacadeService, sseService);
        bridge.attachTraceMetadata("session-1", "turn-1", sampleTrace);

        // Verify update was called on the final assistant message
        ArgumentCaptor<UpdateChatMessageRequest> updateCaptor = ArgumentCaptor.forClass(UpdateChatMessageRequest.class);
        verify(chatMessageFacadeService).updateChatMessage(eq("final-1"), updateCaptor.capture());

        UpdateChatMessageRequest updateReq = updateCaptor.getValue();
        assertThat(updateReq.getMetadata()).isNotNull();
        assertThat(updateReq.getMetadata().getAgentTrace()).isNotNull();
        assertThat(updateReq.getMetadata().getAgentTrace().getMode()).isEqualTo("DEEPTHINK");

        ArgumentCaptor<SseMessage> sseCaptor = ArgumentCaptor.forClass(SseMessage.class);
        verify(sseService).publish(eq("session-1"), sseCaptor.capture());
        assertThat(sseCaptor.getValue().getType()).isEqualTo(SseMessage.Type.AI_GENERATED_CONTENT);
        assertThat(sseCaptor.getValue().getPayload().getMessage().getMetadata().getAgentTrace())
                .isNotNull();
    }

    @Test
    void attachTraceMetadata_skipsWrongTurn() {
        List<ChatMessageDTO> messages = List.of(
                // Assistant from a different turn — should be skipped
                dto("other-1", "turn-other", ChatMessageDTO.RoleType.ASSISTANT,
                        ChatMessageDTO.MetaData.builder().build()),
                // No matching assistant for turn-1
                dto("user-1", "turn-1", ChatMessageDTO.RoleType.USER,
                        ChatMessageDTO.MetaData.builder().build())
        );

        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 50))
                .thenReturn(messages);

        TestableBridge bridge = new TestableBridge(chatMessageFacadeService, sseService);
        bridge.attachTraceMetadata("session-1", "turn-1", sampleTrace);

        // No update should be called — no non-internal assistant for turn-1
        verify(chatMessageFacadeService, never()).updateChatMessage(anyString(), any());
    }

    @Test
    void attachTraceMetadata_skipsInternalAssistant() {
        List<ChatMessageDTO> messages = List.of(
                // Internal assistant — should be skipped even though turn matches
                dto("internal-1", "turn-1", ChatMessageDTO.RoleType.ASSISTANT,
                        ChatMessageDTO.MetaData.builder().internal(true).build()),
                // Internal tool — wrong role
                dto("internal-tool-1", "turn-1", ChatMessageDTO.RoleType.TOOL,
                        ChatMessageDTO.MetaData.builder().internal(true).build()),
                // Real final answer
                dto("final-1", "turn-1", ChatMessageDTO.RoleType.ASSISTANT,
                        ChatMessageDTO.MetaData.builder().build())
        );

        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 50))
                .thenReturn(messages);

        TestableBridge bridge = new TestableBridge(chatMessageFacadeService, sseService);
        bridge.attachTraceMetadata("session-1", "turn-1", sampleTrace);

        // Should update only "final-1", not "internal-1"
        verify(chatMessageFacadeService).updateChatMessage(eq("final-1"), any());
        verify(chatMessageFacadeService, times(1)).updateChatMessage(anyString(), any());
    }

    @Test
    void attachTraceMetadata_preservesExistingMetadata() {
        ChatMessageDTO.MetaData existingMeta = ChatMessageDTO.MetaData.builder()
                .deepThinkPhase("FINAL")
                .planStepId("S1")
                .build();

        List<ChatMessageDTO> messages = List.of(
                dto("final-1", "turn-1", ChatMessageDTO.RoleType.ASSISTANT, existingMeta)
        );

        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 50))
                .thenReturn(messages);

        TestableBridge bridge = new TestableBridge(chatMessageFacadeService, sseService);
        bridge.attachTraceMetadata("session-1", "turn-1", sampleTrace);

        ArgumentCaptor<UpdateChatMessageRequest> updateCaptor = ArgumentCaptor.forClass(UpdateChatMessageRequest.class);
        verify(chatMessageFacadeService).updateChatMessage(eq("final-1"), updateCaptor.capture());

        ChatMessageDTO.MetaData updatedMeta = updateCaptor.getValue().getMetadata();
        // Existing fields should be preserved
        assertThat(updatedMeta.getDeepThinkPhase()).isEqualTo("FINAL");
        assertThat(updatedMeta.getPlanStepId()).isEqualTo("S1");
        // Trace should be added
        assertThat(updatedMeta.getAgentTrace()).isNotNull();
        assertThat(updatedMeta.getAgentTrace().getMode()).isEqualTo("DEEPTHINK");
    }

    // Minimal testable subclass that exposes just attachTraceMetadata
    @SuppressWarnings("unchecked")
    private static class TestableBridge extends AgentMessageBridgeImpl {
        TestableBridge(ChatMessageFacadeService facadeService, SseService sseService) {
            super(sseService, new ChatMessageConverter(new ObjectMapper()),
                    facadeService, null, null, mock(ObjectProvider.class));
        }
    }

    private static ChatMessageDTO dto(String id, String turnId,
                                       ChatMessageDTO.RoleType role,
                                       ChatMessageDTO.MetaData metadata) {
        return ChatMessageDTO.builder()
                .id(id)
                .sessionId("session-1")
                .turnId(turnId)
                .content("content")
                .role(role)
                .metadata(metadata)
                .build();
    }
}
