package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentSessionSummaryResolverTest {

    @Mock
    private ChatSessionSummaryRepository chatSessionSummaryRepository;

    private AgentSessionSummaryResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AgentSessionSummaryResolver(chatSessionSummaryRepository);
    }

    @Test
    void shouldReturnEmptyWhenNoSummaryExists() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(null);

        String result = resolver.resolve("session-1");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenSynopsisIsBlank() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .synopsis("")
                        .build()
        );

        String result = resolver.resolve("session-1");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnSynopsisWhenPresent() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .synopsis("User discussed reimbursement process")
                        .build()
        );

        String result = resolver.resolve("session-1");

        assertThat(result).isEqualTo("User discussed reimbursement process");
    }

    @Test
    void shouldReturnTrimmedSynopsis() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .synopsis("  User discussed reimbursement  ")
                        .build()
        );

        String result = resolver.resolve("session-1");

        assertThat(result).isEqualTo("User discussed reimbursement");
    }

    @Test
    void shouldReturnEmptyWhenSessionIdIsBlank() {
        String result = resolver.resolve("");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenSessionIdIsNull() {
        String result = resolver.resolve(null);

        assertThat(result).isEmpty();
    }
}
