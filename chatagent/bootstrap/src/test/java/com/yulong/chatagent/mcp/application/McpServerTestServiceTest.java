package com.yulong.chatagent.mcp.application;

import com.yulong.chatagent.admin.application.McpFeatureFlag;
import com.yulong.chatagent.admin.application.McpAlertService;
import com.yulong.chatagent.admin.application.McpServerStatusMachine;
import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.mcp.model.McpDiscoveryResult;
import com.yulong.chatagent.mcp.model.McpServerProbeOutcome;
import com.yulong.chatagent.mcp.model.McpRemoteToolDescriptor;
import com.yulong.chatagent.mcp.transport.McpTransportClient;
import com.yulong.chatagent.mcp.transport.McpTransportException;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import com.yulong.chatagent.support.enums.McpServerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpServerTestServiceTest {

    @Mock
    private McpTransportClient transportClient;

    @Mock
    private McpServerRepository serverRepository;

    @Mock
    private McpAlertService alertService;

    private McpServerTestService testService;
    private McpFeatureFlag featureFlag;

    @BeforeEach
    void setUp() {
        featureFlag = new McpFeatureFlag();
        featureFlag.setEnabled(true);
        testService = new McpServerTestService(
                featureFlag,
                transportClient,
                serverRepository,
                new McpServerStatusMachine(),
                alertService
        );
        when(serverRepository.update(any(McpServerDTO.class))).thenReturn(true);
    }

    @Test
    void shouldPersistActiveStateWhenProbeSucceeds() {
        McpServerDTO server = existingServer();
        when(transportClient.discover(server)).thenReturn(new McpDiscoveryResult(
                "2025-06-18",
                "Stub MCP",
                "1.0.0",
                LocalDateTime.now(),
                List.of(new McpRemoteToolDescriptor("search", "Search", "{\"type\":\"object\"}", "hash"))
        ));

        McpServerProbeOutcome outcome = testService.test(server);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.server().getStatus()).isEqualTo(McpServerStatus.ACTIVE);
        assertThat(outcome.server().getConsecutiveFailures()).isZero();
        verify(serverRepository).update(any(McpServerDTO.class));
        verify(alertService).resolveServerRecovered("srv-1");
    }

    @Test
    void shouldPersistFailureStateWhenProbeFails() {
        McpServerDTO server = existingServer();
        when(transportClient.discover(server)).thenThrow(new McpTransportException("MCP_TIMEOUT", "timed out"));

        McpServerProbeOutcome outcome = testService.test(server);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("MCP_TIMEOUT");
        assertThat(outcome.server().getStatus()).isEqualTo(McpServerStatus.STALE);
        assertThat(outcome.server().getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void shouldRaiseAlertWhenProbeFailurePushesServerIntoFailedState() {
        McpServerDTO server = McpServerDTO.builder()
                .id("srv-1")
                .slug("google_search")
                .name("Google Search")
                .protocol(McpProtocol.HTTP)
                .authType(McpAuthType.NONE)
                .endpointUrl("https://example.com/mcp")
                .status(McpServerStatus.STALE)
                .consecutiveFailures(2)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        when(transportClient.discover(server)).thenThrow(new McpTransportException("MCP_TIMEOUT", "timed out"));

        McpServerProbeOutcome outcome = testService.test(server);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.server().getStatus()).isEqualTo(McpServerStatus.FAILED);
        verify(alertService).raiseServerFailed(any(McpServerDTO.class), org.mockito.ArgumentMatchers.eq(3), org.mockito.ArgumentMatchers.eq("MCP_TIMEOUT"), org.mockito.ArgumentMatchers.eq("timed out"));
    }

    private McpServerDTO existingServer() {
        return McpServerDTO.builder()
                .id("srv-1")
                .slug("google_search")
                .name("Google Search")
                .protocol(McpProtocol.HTTP)
                .authType(McpAuthType.NONE)
                .endpointUrl("https://example.com/mcp")
                .status(McpServerStatus.DISABLED)
                .consecutiveFailures(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
