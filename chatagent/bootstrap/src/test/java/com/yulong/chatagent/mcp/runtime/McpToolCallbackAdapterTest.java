package com.yulong.chatagent.mcp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.admin.application.McpFeatureFlag;
import com.yulong.chatagent.mcp.metrics.McpMetricsRecorder;
import com.yulong.chatagent.mcp.model.McpToolCallResult;
import com.yulong.chatagent.mcp.transport.McpTransportClient;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import com.yulong.chatagent.support.enums.McpServerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class McpToolCallbackAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private McpTransportClient transportClient;
    private McpFeatureFlag featureFlag;
    private McpMetricsRecorder metricsRecorder;
    private McpServerDTO server;
    private McpToolCallbackAdapter adapter;

    @BeforeEach
    void setUp() {
        transportClient = mock(McpTransportClient.class);
        featureFlag = new McpFeatureFlag();
        featureFlag.setEnabled(true);
        metricsRecorder = mock(McpMetricsRecorder.class);
        McpRuntimeProtectionProperties protectionProperties = new McpRuntimeProtectionProperties();
        protectionProperties.setRateLimitRequestsPerSecond(10);
        protectionProperties.setRateLimitBurstCapacity(10);
        server = McpServerDTO.builder()
                .id("srv-1")
                .slug("google")
                .name("Google")
                .protocol(McpProtocol.HTTP)
                .authType(McpAuthType.NONE)
                .endpointUrl("https://example.com/mcp")
                .status(McpServerStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        adapter = new McpToolCallbackAdapter(
                "google",
                "search",
                ToolDefinition.builder()
                        .name("mcp_google_search")
                        .description("Search through MCP")
                        .inputSchema("{}")
                        .build(),
                server,
                transportClient,
                featureFlag,
                new McpToolResponseSanitizer(),
                new McpToolErrorEnvelopeFactory(objectMapper),
                new McpServerRateLimiter(protectionProperties),
                new McpServerCircuitBreakerRegistry(protectionProperties, metricsRecorder),
                metricsRecorder
        );
    }

    @Test
    void shouldFastFailWhenMcpFeatureFlagIsDisabled() throws Exception {
        featureFlag.setEnabled(false);

        JsonNode result = objectMapper.readTree(adapter.call("{\"query\":\"news\"}"));

        assertThat(result.path("status").asText()).isEqualTo("error");
        assertThat(result.path("errorCode").asText()).isEqualTo("MCP_DISABLED");
        verifyNoInteractions(transportClient);
    }

    @Test
    void shouldRejectOutboundCallWhenToolContextIsMissing() throws Exception {
        JsonNode result = objectMapper.readTree(adapter.call("{\"query\":\"news\"}"));

        assertThat(result.path("status").asText()).isEqualTo("error");
        assertThat(result.path("errorCode").asText()).isEqualTo("MCP_CONTEXT_MISSING");
        verifyNoInteractions(transportClient);
    }

    @Test
    void shouldCallRemoteToolUsingOriginalNameAndSanitizeResponse() throws Exception {
        when(transportClient.callTool(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("search"), org.mockito.ArgumentMatchers.eq("{\"query\":\"news\"}")))
                .thenReturn(new McpToolCallResult("{\"result\":\"latest headlines\"}"));

        JsonNode result = objectMapper.readTree(adapter.call(
                "{\"query\":\"news\"}",
                new ToolContext(Map.of(
                        "userId", "user-1",
                        "sessionId", "session-1",
                        "turnId", "turn-1"
                ))
        ));

        assertThat(result.path("status").asText()).isEqualTo("ok");
        assertThat(result.path("server").asText()).isEqualTo("google");
        assertThat(result.path("tool").asText()).isEqualTo("mcp_google_search");
        assertThat(result.path("content").asText())
                .contains("[TOOL_RESPONSE_START]")
                .contains("latest headlines")
                .contains("[TOOL_RESPONSE_END]");
        assertThat(result.toString()).doesNotContain("user-1").doesNotContain("session-1").doesNotContain("turn-1");
        verify(transportClient).callTool(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("search"), org.mockito.ArgumentMatchers.eq("{\"query\":\"news\"}"));
    }

    @Test
    void shouldRejectCallWhenRateLimiterDeniesRequest() throws Exception {
        McpRuntimeProtectionProperties protectionProperties = new McpRuntimeProtectionProperties();
        protectionProperties.setRateLimitRequestsPerSecond(1);
        protectionProperties.setRateLimitBurstCapacity(1);
        when(transportClient.callTool(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("search"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new McpToolCallResult("{\"result\":\"ok\"}"));
        adapter = new McpToolCallbackAdapter(
                "google",
                "search",
                ToolDefinition.builder().name("mcp_google_search").description("Search through MCP").inputSchema("{}").build(),
                server,
                transportClient,
                featureFlag,
                new McpToolResponseSanitizer(),
                new McpToolErrorEnvelopeFactory(objectMapper),
                new McpServerRateLimiter(protectionProperties, () -> 0L),
                new McpServerCircuitBreakerRegistry(protectionProperties, metricsRecorder),
                metricsRecorder
        );

        JsonNode first = objectMapper.readTree(adapter.call(
                "{\"query\":\"first\"}",
                new ToolContext(Map.of("userId", "user-1", "sessionId", "session-1", "turnId", "turn-1"))
        ));
        JsonNode second = objectMapper.readTree(adapter.call(
                "{\"query\":\"second\"}",
                new ToolContext(Map.of("userId", "user-1", "sessionId", "session-1", "turnId", "turn-1"))
        ));

        assertThat(first.path("status").asText()).isEqualTo("ok");
        assertThat(second.path("status").asText()).isEqualTo("error");
        assertThat(second.path("errorCode").asText()).isEqualTo("MCP_RATE_LIMITED");
        verify(transportClient, times(1)).callTool(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("search"), org.mockito.ArgumentMatchers.anyString());
    }
}
