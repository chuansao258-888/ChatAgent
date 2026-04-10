package com.yulong.chatagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.yulong.chatagent.admin.application.McpAlertService;
import com.yulong.chatagent.admin.application.McpCredentialCipher;
import com.yulong.chatagent.admin.application.McpFeatureFlag;
import com.yulong.chatagent.admin.application.McpServerStatusMachine;
import com.yulong.chatagent.admin.application.McpToolNameNormalizer;
import com.yulong.chatagent.admin.application.ToolFacadeServiceImpl;
import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.admin.port.McpToolCatalogRepository;
import com.yulong.chatagent.agent.runtime.AgentToolCallbackFactory;
import com.yulong.chatagent.intent.model.IntentToolScopeMode;
import com.yulong.chatagent.mcp.application.McpCatalogSyncService;
import com.yulong.chatagent.mcp.application.McpServerTestService;
import com.yulong.chatagent.mcp.metrics.McpMetricsRecorder;
import com.yulong.chatagent.mcp.runtime.McpRolloutPolicy;
import com.yulong.chatagent.mcp.runtime.McpRolloutProperties;
import com.yulong.chatagent.mcp.runtime.McpRuntimeProtectionProperties;
import com.yulong.chatagent.mcp.runtime.McpRuntimeToolRegistry;
import com.yulong.chatagent.mcp.runtime.McpToolDefinitionFactory;
import com.yulong.chatagent.mcp.runtime.McpToolErrorEnvelopeFactory;
import com.yulong.chatagent.mcp.runtime.McpToolResponseSanitizer;
import com.yulong.chatagent.mcp.transport.McpHandshakeCache;
import com.yulong.chatagent.mcp.transport.McpTransportProperties;
import com.yulong.chatagent.mcp.transport.WebClientMcpTransportClient;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import com.yulong.chatagent.support.enums.McpServerStatus;
import com.yulong.chatagent.support.enums.McpToolCatalogStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class McpEndToEndIntegrationTest {

    private static final String BASE64_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer httpServer;

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void shouldSyncCatalogAndExecuteRemoteToolThroughRuntimeFactory() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpServer.createContext("/mcp", new SuccessHandler());
        httpServer.start();

        InMemoryMcpServerRepository serverRepository = new InMemoryMcpServerRepository();
        InMemoryMcpToolCatalogRepository toolCatalogRepository = new InMemoryMcpToolCatalogRepository();
        McpServerDTO server = McpServerDTO.builder()
                .id("srv-1")
                .slug("google")
                .name("Google Search")
                .protocol(McpProtocol.HTTP)
                .authType(McpAuthType.NONE)
                .endpointUrl("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/mcp")
                .status(McpServerStatus.DISABLED)
                .consecutiveFailures(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        serverRepository.save(server);

        McpFeatureFlag featureFlag = new McpFeatureFlag();
        featureFlag.setEnabled(true);
        WebClientMcpTransportClient transportClient = new WebClientMcpTransportClient(
                objectMapper,
                new McpCredentialCipher(BASE64_KEY, "v1"),
                new McpTransportProperties(),
                new McpHandshakeCache()
        );
        McpServerTestService serverTestService = new McpServerTestService(
                featureFlag,
                transportClient,
                serverRepository,
                new McpServerStatusMachine(),
                mock(McpAlertService.class)
        );
        McpCatalogSyncService catalogSyncService = new McpCatalogSyncService(
                featureFlag,
                transportClient,
                toolCatalogRepository,
                new McpToolNameNormalizer(),
                serverTestService
        );

        var syncOutcome = catalogSyncService.sync(server);
        assertThat(syncOutcome.success()).isTrue();
        assertThat(syncOutcome.discoveryResult().tools()).hasSize(1);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        McpMetricsRecorder metricsRecorder = new McpMetricsRecorder(beanFactory.getBeanProvider(MeterRegistry.class));
        McpRuntimeToolRegistry runtimeToolRegistry = new McpRuntimeToolRegistry(
                featureFlag,
                serverRepository,
                toolCatalogRepository,
                new McpToolDefinitionFactory(),
                transportClient,
                new McpToolResponseSanitizer(),
                new McpToolErrorEnvelopeFactory(objectMapper),
                new com.yulong.chatagent.mcp.runtime.McpServerRateLimiter(new McpRuntimeProtectionProperties()),
                new com.yulong.chatagent.mcp.runtime.McpServerCircuitBreakerRegistry(new McpRuntimeProtectionProperties(), metricsRecorder),
                metricsRecorder
        );
        ToolFacadeServiceImpl toolFacadeService = new ToolFacadeServiceImpl(List.of(), runtimeToolRegistry);
        McpRolloutProperties rolloutProperties = new McpRolloutProperties();
        rolloutProperties.setMode("AGENT_ALLOWLIST");
        rolloutProperties.setAllowedAgentIds(List.of("assistant-1"));
        AgentToolCallbackFactory callbackFactory = new AgentToolCallbackFactory(
                toolFacadeService,
                new McpRolloutPolicy(rolloutProperties),
                IntentToolScopeMode.STRICT_TOOL_ONLY
        );

        List<ToolCallback> callbacks = callbackFactory.create(AgentDTO.builder()
                .id("assistant-1")
                .allowedTools(List.of("mcp_google_search"))
                .build());

        assertThat(callbacks).hasSize(1);
        JsonNode result = objectMapper.readTree(callbacks.get(0).call(
                "{\"query\":\"release notes\"}",
                new ToolContext(Map.of(
                        "userId", "user-1",
                        "sessionId", "session-1",
                        "turnId", "turn-1"
                ))
        ));
        assertThat(result.path("status").asText()).isEqualTo("ok");
        assertThat(result.path("tool").asText()).isEqualTo("mcp_google_search");
        assertThat(result.path("content").asText()).contains("release notes");
    }

    private static final class InMemoryMcpServerRepository implements McpServerRepository {

        private final Map<String, McpServerDTO> servers = new LinkedHashMap<>();

        @Override
        public List<McpServerDTO> findAll() {
            return new ArrayList<>(servers.values());
        }

        @Override
        public McpServerDTO findById(String id) {
            return servers.get(id);
        }

        @Override
        public McpServerDTO findBySlug(String slug) {
            return servers.values().stream().filter(server -> slug.equals(server.getSlug())).findFirst().orElse(null);
        }

        @Override
        public boolean save(McpServerDTO server) {
            servers.put(server.getId(), server);
            return true;
        }

        @Override
        public boolean update(McpServerDTO server) {
            servers.put(server.getId(), server);
            return true;
        }

        @Override
        public boolean softDelete(String id, LocalDateTime deletedAt, LocalDateTime updatedAt) {
            servers.remove(id);
            return true;
        }
    }

    private static final class InMemoryMcpToolCatalogRepository implements McpToolCatalogRepository {

        private final Map<String, List<McpToolCatalogDTO>> rowsByServerId = new LinkedHashMap<>();

        @Override
        public List<McpToolCatalogDTO> findByServerId(String serverId) {
            return new ArrayList<>(rowsByServerId.getOrDefault(serverId, List.of()));
        }

        @Override
        public boolean upsert(McpToolCatalogDTO toolCatalog) {
            List<McpToolCatalogDTO> rows = new ArrayList<>(rowsByServerId.getOrDefault(toolCatalog.getServerId(), List.of()));
            rows.removeIf(existing -> existing.getRemoteOriginalName().equals(toolCatalog.getRemoteOriginalName()));
            rows.add(toolCatalog);
            rowsByServerId.put(toolCatalog.getServerId(), rows);
            return true;
        }

        @Override
        public int markMissingAsStale(String serverId, List<String> activeRemoteOriginalNames, LocalDateTime lastSyncedAt, LocalDateTime updatedAt) {
            List<McpToolCatalogDTO> rows = new ArrayList<>(rowsByServerId.getOrDefault(serverId, List.of()));
            int stale = 0;
            List<McpToolCatalogDTO> updatedRows = new ArrayList<>();
            for (McpToolCatalogDTO row : rows) {
                if (activeRemoteOriginalNames.contains(row.getRemoteOriginalName())) {
                    updatedRows.add(row);
                    continue;
                }
                stale++;
                updatedRows.add(McpToolCatalogDTO.builder()
                        .id(row.getId())
                        .serverId(row.getServerId())
                        .remoteOriginalName(row.getRemoteOriginalName())
                        .toolDescription(row.getToolDescription())
                        .exposedModelName(row.getExposedModelName())
                        .schemaJson(row.getSchemaJson())
                        .schemaHash(row.getSchemaHash())
                        .status(McpToolCatalogStatus.STALE)
                        .createdAt(row.getCreatedAt())
                        .updatedAt(updatedAt)
                        .lastSyncedAt(lastSyncedAt)
                        .build());
            }
            rowsByServerId.put(serverId, updatedRows);
            return stale;
        }

        @Override
        public boolean softDeleteByServerId(String serverId, LocalDateTime deletedAt, LocalDateTime updatedAt) {
            rowsByServerId.remove(serverId);
            return true;
        }
    }

    private static final class SuccessHandler implements HttpHandler {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body;
            try (InputStream inputStream = exchange.getRequestBody()) {
                body = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (body.contains("\"method\":\"initialize\"")) {
                writeJson(exchange, Map.of(
                        "jsonrpc", "2.0",
                        "id", "ignored",
                        "result", Map.of(
                                "protocolVersion", "2025-06-18",
                                "serverInfo", Map.of("name", "Stub MCP", "version", "1.0.0"),
                                "capabilities", Map.of("tools", Map.of())
                        )
                ));
                return;
            }
            if (body.contains("\"method\":\"notifications/initialized\"")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            if (body.contains("\"method\":\"tools/list\"")) {
                writeJson(exchange, Map.of(
                        "jsonrpc", "2.0",
                        "id", "ignored",
                        "result", Map.of(
                                "tools", new Object[]{
                                        Map.of(
                                                "name", "search",
                                                "description", "Search the web",
                                                "inputSchema", Map.of(
                                                        "type", "object",
                                                        "properties", Map.of("query", Map.of("type", "string"))
                                                )
                                        )
                                }
                        )
                ));
                return;
            }
            if (body.contains("\"method\":\"tools/call\"")) {
                writeJson(exchange, Map.of(
                        "jsonrpc", "2.0",
                        "id", "ignored",
                        "result", Map.of(
                                "content", new Object[]{
                                        Map.of("type", "text", "text", "top result for " + body)
                                }
                        )
                ));
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }

        private void writeJson(HttpExchange exchange, Map<String, Object> payload) throws IOException {
            byte[] bytes = objectMapper.writeValueAsBytes(payload);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        }
    }
}
