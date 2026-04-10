package com.yulong.chatagent.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.yulong.chatagent.mcp.application.McpCredentialCipher;
import com.yulong.chatagent.mcp.model.McpDiscoveryResult;
import com.yulong.chatagent.mcp.model.McpToolCallResult;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import com.yulong.chatagent.support.enums.McpServerStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebClientMcpTransportClientTest {

    private static final String BASE64_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private HttpServer httpServer;

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void shouldDiscoverToolsOverHttpTransport() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpServer.createContext("/mcp", new StubMcpHttpHandler());
        httpServer.start();

        McpTransportProperties properties = new McpTransportProperties();
        WebClientMcpTransportClient transportClient = new WebClientMcpTransportClient(
                new ObjectMapper(),
                new McpCredentialCipher(BASE64_KEY, "v1"),
                properties,
                new McpHandshakeCache()
        );

        McpServerDTO server = McpServerDTO.builder()
                .id("srv-1")
                .slug("google")
                .name("Google")
                .protocol(McpProtocol.HTTP)
                .authType(McpAuthType.NONE)
                .endpointUrl("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/mcp")
                .status(McpServerStatus.DISABLED)
                .consecutiveFailures(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        McpDiscoveryResult result = transportClient.discover(server);

        assertThat(result.negotiatedProtocolVersion()).isEqualTo("2025-06-18");
        assertThat(result.remoteServerName()).isEqualTo("Stub MCP");
        assertThat(result.tools()).hasSize(1);
        assertThat(result.tools().get(0).remoteOriginalName()).isEqualTo("search");
    }

    @Test
    void shouldCallToolOverHttpTransport() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpServer.createContext("/mcp", new StubMcpHttpHandler());
        httpServer.start();

        McpTransportProperties properties = new McpTransportProperties();
        WebClientMcpTransportClient transportClient = new WebClientMcpTransportClient(
                new ObjectMapper(),
                new McpCredentialCipher(BASE64_KEY, "v1"),
                properties,
                new McpHandshakeCache()
        );

        McpServerDTO server = McpServerDTO.builder()
                .id("srv-1")
                .slug("google")
                .name("Google")
                .protocol(McpProtocol.HTTP)
                .authType(McpAuthType.NONE)
                .endpointUrl("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/mcp")
                .status(McpServerStatus.ACTIVE)
                .consecutiveFailures(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        McpToolCallResult result = transportClient.callTool(server, "search", "{\"query\":\"release notes\"}");

        assertThat(result.payload()).contains("release notes");
        assertThat(result.payload()).contains("top result");
    }

    private static final class StubMcpHttpHandler implements HttpHandler {

        private final ObjectMapper objectMapper = new ObjectMapper();
        private final String expectedSessionId = UUID.randomUUID().toString();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body;
            try (InputStream inputStream = exchange.getRequestBody()) {
                body = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (body.contains("\"method\":\"initialize\"")) {
                exchange.getResponseHeaders().add("Mcp-Session-Id", expectedSessionId);
                writeJson(exchange, Map.of(
                        "jsonrpc", "2.0",
                        "id", "ignored",
                        "result", Map.of(
                                "protocolVersion", "2025-06-18",
                                "serverInfo", Map.of(
                                        "name", "Stub MCP",
                                        "version", "1.0.0"
                                ),
                                "capabilities", Map.of("tools", Map.of())
                        )
                ));
                return;
            }
            if (body.contains("\"method\":\"notifications/initialized\"")) {
                if (!expectedSessionId.equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))) {
                    exchange.sendResponseHeaders(400, -1);
                    exchange.close();
                    return;
                }
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            if (body.contains("\"method\":\"tools/list\"")) {
                if (!expectedSessionId.equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))) {
                    exchange.sendResponseHeaders(400, -1);
                    exchange.close();
                    return;
                }
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
                                                        "properties", Map.of(
                                                                "query", Map.of("type", "string")
                                                        )
                                                )
                                        )
                                }
                        )
                ));
                return;
            }
            if (body.contains("\"method\":\"tools/call\"")) {
                if (!expectedSessionId.equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))) {
                    exchange.sendResponseHeaders(400, -1);
                    exchange.close();
                    return;
                }
                writeJson(exchange, Map.of(
                        "jsonrpc", "2.0",
                        "id", "ignored",
                        "result", Map.of(
                                "content", new Object[]{
                                        Map.of(
                                                "type", "text",
                                                "text", "top result for " + body
                                        )
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
