package com.yulong.chatagent.support.persistence.adapter.conversation;

import com.yulong.chatagent.agent.tools.ToolExecutionLedgerPort;
import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.support.persistence.mapper.ToolExecutionJournalMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MyBatisToolExecutionLedgerPortTest {

    @Test
    void atomicCommitUsesPersistedMessageIdAndMatchingAttempt() {
        ToolExecutionJournalMapper mapper = mock(ToolExecutionJournalMapper.class);
        ChatMessageFacadeService messages = mock(ChatMessageFacadeService.class);
        when(messages.createChatMessage(any(ChatMessageDTO.class))).thenReturn(
                CreateChatMessageResponse.builder().chatMessageId("message-1").turnSeq(7L).build());
        when(mapper.casToTerminal("exec-1", "DISPATCHING", "SUCCEEDED", 2,
                "message-1", "response-hash", null)).thenReturn(1);
        MyBatisToolExecutionLedgerPort port = new MyBatisToolExecutionLedgerPort(mapper, messages);

        ToolExecutionLedgerPort.PersistedToolResponse persisted = port.commitTerminalResponse(
                "exec-1", "DISPATCHING",
                new ToolExecutionLedgerPort.TerminalUpdate(
                        "SUCCEEDED", null, "response-hash", null, 2),
                request(false));

        assertThat(persisted.messageId()).isEqualTo("message-1");
        assertThat(persisted.turnSeq()).isEqualTo(7L);
        verify(mapper).casToTerminal("exec-1", "DISPATCHING", "SUCCEEDED", 2,
                "message-1", "response-hash", null);
    }

    @Test
    void terminalCasConflictFailsTheTransactionBoundary() {
        ToolExecutionJournalMapper mapper = mock(ToolExecutionJournalMapper.class);
        ChatMessageFacadeService messages = mock(ChatMessageFacadeService.class);
        when(messages.createChatMessage(any(ChatMessageDTO.class))).thenReturn(
                CreateChatMessageResponse.builder().chatMessageId("message-1").turnSeq(7L).build());
        when(mapper.casToTerminal(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), nullable(String.class))).thenReturn(0);
        MyBatisToolExecutionLedgerPort port = new MyBatisToolExecutionLedgerPort(mapper, messages);

        assertThatThrownBy(() -> port.commitTerminalResponse(
                "exec-1", "DISPATCHING",
                new ToolExecutionLedgerPort.TerminalUpdate(
                        "SUCCEEDED", null, "response-hash", null, 1),
                request(false)))
                .isInstanceOf(MyBatisToolExecutionLedgerPort.ToolTerminalCommitConflictException.class);
    }

    private static ToolExecutionLedgerPort.ToolResponseCommitRequest request(boolean internal) {
        return new ToolExecutionLedgerPort.ToolResponseCommitRequest(
                "session-1", "turn-1",
                new ToolResponseMessage.ToolResponse("call-1", "tool-1", "ok"),
                internal, null, null);
    }
}
