package com.yulong.chatagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.yulong.chatagent.admin.application.McpCredentialCipher;
import com.yulong.chatagent.admin.application.McpFeatureFlag;
import com.yulong.chatagent.mcp.metrics.McpMetricsRecorder;
import com.yulong.chatagent.mcp.runtime.McpRuntimeProtectionProperties;
import com.yulong.chatagent.mcp.runtime.McpServerCircuitBreakerRegistry;
import com.yulong.chatagent.mcp.runtime.McpServerRateLimiter;
import com.yulong.chatagent.mcp.runtime.McpToolCallbackAdapter;
import com.yulong.chatagent.mcp.runtime.McpToolErrorEnvelopeFactory;
import com.yulong.chatagent.mcp.runtime.McpToolResponseSanitizer;
import com.yulong.chatagent.mcp.transport.McpHandshakeCache;
import com.yulong.chatagent.mcp.transport.McpTransportException;
import com.yulong.chatagent.mcp.transport.McpTransportProperties;
import com.yulong.chatagent.mcp.transport.WebClientMcpTransportClient;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import com.yulong.chatagent.support.enums.McpServerStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpFailureInjectionIntegrationTest {

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
    void shouldSurfaceStructuredErrorWhenRemoteToolReturns500() throws Exception {
        startServer(new ToolCall500Handler());
        JsonNode result = objectMapper.readTree(adapterFor(transportClient(131072)).call(
                "{\"query\":\"news\"}",
                new ToolContext(Map.of("userId", "u1", "sessionId", "s1", "turnId", "t1"))
        ));

        assertThat(result.path("status").asText()).isEqualTo("error");
        assertThat(result.path("errorCode").asText()).isEqualTo("MCP_HTTP_500");
    }

    @Test
    void shouldFailDiscoveryWhenToolsListReturnsUnauthorized() throws Exception {
        startServer(new ToolsList401Handler());

        assertThatThrownBy(() -> transportClient(131072).discover(server()))
                .isInstanceOf(McpTransportException.class)
                .hasMessageContaining("HTTP 401");
    }

    @Test
    void shouldReturnStructuredErrorWhenRemotePayloadIsMalformed() throws Exception {
        startServer(new MalformedToolCallHandler());
        JsonNode result = objectMapper.readTree(adapterFor(transportClient(131072)).call(
                "{\"query\":\"news\"}",
                new ToolContext(Map.of("userId", "u1", "sessionId", "s1", "turnId", "t1"))
        ));

        assertThat(result.path("status").asText()).isEqualTo("error");
        assertThat(result.path("errorCode").asText()).isEqualTo("MCP_JSONRPC_DECODE_FAILED");
    }

    @Test
    void shouldRejectOversizedResponseAtTransportBoundary() throws Exception {
        startServer(new OversizedToolCallHandler());

        assertThatThrownBy(() -> transportClient(512).callTool(server(), "search", "{\"query\":\"large\"}"))
                .isInstanceOf(McpTransportException.class)
                .hasMessageContaining("limit");
    }

    private void startServer(HttpHandler handler) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpServer.createContext("/mcp", handler);
        httpServer.start();
    }

    private WebClientMcpTransportClient transportClient(int maxInMemorySizeBytes) {
        McpTransportProperties properties = new McpTransportProperties();
        properties.setMaxInMemorySizeBytes(maxInMemorySizeBytes);
        return new WebClientMcpTransportClient(
                objectMapper,
                new McpCredentialCipher(BASE64_KEY, "v1"),
                properties,
                new McpHandshakeCache()
        );
    }

    private McpToolCallbackAdapter adapterFor(WebClientMcpTransportClient transportClient) {
        McpFeatureFlag featureFlag = new McpFeatureFlag();
        featureFlag.setEnabled(true);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        McpMetricsRecorder metricsRecorder = new McpMetricsRecorder(beanFactory.getBeanProvider(MeterRegistry.class));
        return new McpToolCallbackAdapter(
                "google",
                "search",
                ToolDefinition.builder().name("mcp_google_search").description("Search via MCP").inputSchema("{}").build(),
                server(),
                transportClient,
                featureFlag,
                new McpToolResponseSanitizer(),
                new McpToolErrorEnvelopeFactory(objectMapper),
                new McpServerRateLimiter(new McpRuntimeProtectionProperties()),
                new McpServerCircuitBreakerRegistry(new McpRuntimeProtectionProperties(), metricsRecorder),
                metricsRecorder
        );
    }

    private McpServerDTO server() {
        return McpServerDTO.builder()
                .id("srv-1")
                .slug("google")
                .name("Google Search")
                .protocol(McpProtocol.HTTP)
                .authType(McpAuthType.NONE)
                .endpointUrl("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/mcp")
                .status(McpServerStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private abstract static class BaseHttpHandler implements HttpHandler {

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
            handleBusiness(exchange, body);
        }

        protected abstract void handleBusiness(HttpExchange exchange, String body) throws IOException;

        protected void writeJson(HttpExchange exchange, Map<String, Object> payload) throws IOException {
            byte[] bytes = objectMapper.writeValueAsBytes(payload);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        }
    }

    private static final class ToolCall500Handler extends BaseHttpHandler {
        @Override
        protected void handleBusiness(HttpExchange exchange, String body) throws IOException {
            if (body.contains("\"method\":\"tools/list\"")) {
                writeJson(exchange, Map.of("jsonrpc", "2.0", "id", "ignored", "result", Map.of("tools", new Object[]{Map.of("name", "search", "inputSchema", Map.of("type", "object"))})));
                return;
            }
            if (body.contains("\"method\":\"tools/call\"")) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }
    }

    private static final class ToolsList401Handler extends BaseHttpHandler {
        @Override
        protected void handleBusiness(HttpExchange exchange, String body) throws IOException {
            if (body.contains("\"method\":\"tools/list\"")) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }
    }

    private static final class MalformedToolCallHandler extends BaseHttpHandler {
        @Override
        protected void handleBusiness(HttpExchange exchange, String body) throws IOException {
            if (body.contains("\"method\":\"tools/list\"")) {
                writeJson(exchange, Map.of("jsonrpc", "2.0", "id", "ignored", "result", Map.of("tools", new Object[]{Map.of("name", "search", "inputSchema", Map.of("type", "object"))})));
                return;
            }
            if (body.contains("\"method\":\"tools/call\"")) {
                byte[] bytes = "{not-json".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(bytes);
                }
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }
    }

    private static final class OversizedToolCallHandler extends BaseHttpHandler {
        @Override
        protected void handleBusiness(HttpExchange exchange, String body) throws IOException {
            if (body.contains("\"method\":\"tools/list\"")) {
                writeJson(exchange, Map.of("jsonrpc", "2.0", "id", "ignored", "result", Map.of("tools", new Object[]{Map.of("name", "search", "inputSchema", Map.of("type", "object"))})));
                return;
            }
            if (body.contains("\"method\":\"tools/call\"")) {
                String largeText = "x".repeat(20_000);
                byte[] bytes = ("{\"jsonrpc\":\"2.0\",\"id\":\"ignored\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + largeText + "\"}]}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(bytes);
                }
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }
    }
}
