package com.yulong.chatagent.agent;

import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3: 验证 status-only SSE 事件的结构契约。
 *
 * <p>Status 事件（AI_PLANNING / AI_EXECUTING / AI_THINKING）与 content 事件（AI_GENERATED_CONTENT）
 * 的 payload/metadata 结构不同：
 * <ul>
 *   <li>Status 事件：payload 只有 statusText + turnId，metadata 为 null</li>
 *   <li>Content 事件：payload 含 message，metadata 含 chatMessageId</li>
 * </ul>
 */
class StatusSseContractTest {

    @Test
    @DisplayName("Status SSE event has null metadata and no message")
    void statusEvent_shouldHaveNullMetadataAndNoMessage() {
        SseMessage statusEvent = SseMessage.builder()
                .type(SseMessage.Type.AI_PLANNING)
                .payload(SseMessage.Payload.builder()
                        .statusText("正在规划...")
                        .turnId("turn-1")
                        .build())
                .build();

        assertThat(statusEvent.getType()).isEqualTo(SseMessage.Type.AI_PLANNING);
        assertThat(statusEvent.getPayload().getStatusText()).isEqualTo("正在规划...");
        assertThat(statusEvent.getPayload().getTurnId()).isEqualTo("turn-1");
        assertThat(statusEvent.getPayload().getMessage()).isNull();
        assertThat(statusEvent.getPayload().getDone()).isNull();
        assertThat(statusEvent.getMetadata()).isNull();
    }

    @Test
    @DisplayName("AI_EXECUTING status event carries step progress")
    void executingEvent_shouldCarryStepProgress() {
        SseMessage event = SseMessage.builder()
                .type(SseMessage.Type.AI_EXECUTING)
                .payload(SseMessage.Payload.builder()
                        .statusText("正在执行 S2/5...")
                        .turnId("turn-1")
                        .build())
                .build();

        assertThat(event.getType()).isEqualTo(SseMessage.Type.AI_EXECUTING);
        assertThat(event.getPayload().getStatusText()).contains("S2/5");
        assertThat(event.getMetadata()).isNull();
    }

    @Test
    @DisplayName("AI_THINKING status event for reflection phase")
    void thinkingStatusEvent_shouldWork() {
        SseMessage event = SseMessage.builder()
                .type(SseMessage.Type.AI_THINKING)
                .payload(SseMessage.Payload.builder()
                        .statusText("正在反思...")
                        .turnId("turn-1")
                        .build())
                .build();

        assertThat(event.getType()).isEqualTo(SseMessage.Type.AI_THINKING);
        assertThat(event.getPayload().getStatusText()).isEqualTo("正在反思...");
        assertThat(event.getMetadata()).isNull();
    }

    @Test
    @DisplayName("Content SSE event has message and metadata")
    void contentEvent_shouldHaveMessageAndMetadata() {
        SseMessage contentEvent = SseMessage.builder()
                .type(SseMessage.Type.AI_GENERATED_CONTENT)
                .payload(SseMessage.Payload.builder()
                        .message(com.yulong.chatagent.conversation.model.vo.ChatMessageVO.builder()
                                .id("msg-1")
                                .sessionId("session-1")
                                .role(ChatMessageDTO.RoleType.ASSISTANT)
                                .content("Hello")
                                .build())
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .chatMessageId("msg-1")
                        .build())
                .build();

        assertThat(contentEvent.getType()).isEqualTo(SseMessage.Type.AI_GENERATED_CONTENT);
        assertThat(contentEvent.getPayload().getMessage()).isNotNull();
        assertThat(contentEvent.getPayload().getMessage().getContent()).isEqualTo("Hello");
        assertThat(contentEvent.getMetadata()).isNotNull();
        assertThat(contentEvent.getMetadata().getChatMessageId()).isEqualTo("msg-1");
    }
}
