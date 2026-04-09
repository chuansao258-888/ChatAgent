package com.yulong.chatagent.admin.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.admin.port.McpAlertEventRepository;
import com.yulong.chatagent.support.dto.McpAlertEventDTO;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.enums.McpAlertStatus;
import com.yulong.chatagent.support.enums.McpAlertType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpAlertServiceTest {

    @Mock
    private McpAlertEventRepository alertEventRepository;

    private McpAlertService alertService;

    @BeforeEach
    void setUp() {
        alertService = new McpAlertService(alertEventRepository, new ObjectMapper());
    }

    @Test
    void shouldCreateOpenAlertWhenServerFails() {
        when(alertEventRepository.findOpenByServerAndType("srv-1", McpAlertType.SERVER_FAILED)).thenReturn(null);
        when(alertEventRepository.save(any(McpAlertEventDTO.class))).thenReturn(true);

        alertService.raiseServerFailed(
                McpServerDTO.builder().id("srv-1").slug("google").build(),
                3,
                "MCP_TIMEOUT",
                "timed out"
        );

        ArgumentCaptor<McpAlertEventDTO> captor = ArgumentCaptor.forClass(McpAlertEventDTO.class);
        verify(alertEventRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(McpAlertStatus.OPEN);
        assertThat(captor.getValue().getAlertType()).isEqualTo(McpAlertType.SERVER_FAILED);
        assertThat(captor.getValue().getSummary()).contains("FAILED");
        assertThat(captor.getValue().getDetailsJson()).contains("MCP_TIMEOUT");
    }

    @Test
    void shouldResolveSchemaDriftAlertByServer() {
        alertService.resolveSchemaDrift("srv-1");

        verify(alertEventRepository).resolveOpenByServerAndType(eq("srv-1"), eq(McpAlertType.SCHEMA_DRIFT), any(LocalDateTime.class), any(LocalDateTime.class));
    }
}
