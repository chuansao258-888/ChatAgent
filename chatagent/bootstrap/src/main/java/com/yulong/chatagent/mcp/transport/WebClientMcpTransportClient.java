package com.yulong.chatagent.mcp.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.admin.application.McpCredentialCipher;
import com.yulong.chatagent.mcp.model.McpDiscoveryResult;
import com.yulong.chatagent.mcp.model.McpRemoteToolDescriptor;
import com.yulong.chatagent.mcp.model.McpToolCallResult;
import com.yulong.chatagent.mcp.protocol.McpInitializeResult;
import com.yulong.chatagent.mcp.protocol.McpJsonRpcResponse;
import com.yulong.chatagent.mcp.protocol.McpRemoteTool;
import com.yulong.chatagent.mcp.protocol.McpToolsListResult;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight MCP client for admin-side initialize + tools/list calls.
 */
@Component
public class WebClientMcpTransportClient implements McpTransportClient {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    private static final String HEADER_SESSION_ID = "Mcp-Session-Id";

    private final ObjectMapper objectMapper;
    private final McpCredentialCipher credentialCipher;
    private final McpTransportProperties properties;
    private final McpHandshakeCache handshakeCache;

    public WebClientMcpTransportClient(ObjectMapper objectMapper,
                                       McpCredentialCipher credentialCipher,
                                       McpTransportProperties properties,
                                       McpHandshakeCache handshakeCache) {
        this.objectMapper = objectMapper;
        this.credentialCipher = credentialCipher;
        this.properties = properties;
        this.handshakeCache = handshakeCache;
    }

    @Override
    public McpDiscoveryResult discover(McpServerDTO server) {
        return handshakeCache.runSingleFlight(server.getId(), () -> switch (server.getProtocol()) {
            case HTTP -> discoverOverHttp(server);
            case SSE -> discoverOverLegacySse(server);
        });
    }

    @Override
    public McpToolCallResult callTool(McpServerDTO server, String remoteToolName, String jsonArguments) {
        return handshakeCache.runSingleFlight(server.getId(), () -> switch (server.getProtocol()) {
            case HTTP -> callToolOverHttp(server, remoteToolName, jsonArguments);
            case SSE -> callToolOverLegacySse(server, remoteToolName, jsonArguments);
        });
    }

    private McpDiscoveryResult discoverOverHttp(McpServerDTO server) {
        WebClient client = buildWebClient(server);
        String preferredVersion = properties.getPreferredHttpProtocolVersion();

        HttpJsonRpcExchange<McpInitializeResult> initializeExchange = invokeHttpRequest(
                client,
                server.getEndpointUrl(),
                null,
                null,
                buildRequest("initialize", buildInitializeParams(preferredVersion), false),
                McpInitializeResult.class,
                null
        );
        McpInitializeResult initializeResult = initializeExchange.result();
        String negotiatedVersion = validateNegotiatedVersion(initializeResult == null ? null : initializeResult.protocolVersion(), server.getProtocol());
        sendHttpNotification(
                client,
                server.getEndpointUrl(),
                negotiatedVersion,
                initializeExchange.sessionId(),
                buildRequest("notifications/initialized", Map.of(), true)
        );
        HttpJsonRpcExchange<McpToolsListResult> toolsListExchange = invokeHttpRequest(
                client,
                server.getEndpointUrl(),
                negotiatedVersion,
                initializeExchange.sessionId(),
                buildRequest("tools/list", Map.of(), false),
                McpToolsListResult.class,
                initializeExchange.sessionId()
        );
        McpToolsListResult toolsListResult = toolsListExchange.result();
        return toDiscovery(initializeResult, negotiatedVersion, toolsListResult);
    }

    private McpDiscoveryResult discoverOverLegacySse(McpServerDTO server) {
        try (LegacySseSession session = openLegacySseSession(server, buildWebClient(server))) {
            String preferredVersion = properties.getPreferredSseProtocolVersion();
            McpInitializeResult initializeResult = session.request(
                    buildRequest("initialize", buildInitializeParams(preferredVersion), false),
                    McpInitializeResult.class
            );
            String negotiatedVersion = validateNegotiatedVersion(initializeResult == null ? null : initializeResult.protocolVersion(), server.getProtocol());
            session.notify(buildRequest("notifications/initialized", Map.of(), true));
            McpToolsListResult toolsListResult = session.request(
                    buildRequest("tools/list", Map.of(), false),
                    McpToolsListResult.class
            );
            return toDiscovery(initializeResult, negotiatedVersion, toolsListResult);
        }
    }

    private McpToolCallResult callToolOverHttp(McpServerDTO server, String remoteToolName, String jsonArguments) {
        WebClient client = buildWebClient(server);
        String preferredVersion = properties.getPreferredHttpProtocolVersion();
        HttpJsonRpcExchange<McpInitializeResult> initializeExchange = invokeHttpRequest(
                client,
                server.getEndpointUrl(),
                null,
                null,
                buildRequest("initialize", buildInitializeParams(preferredVersion), false),
                McpInitializeResult.class,
                null
        );
        McpInitializeResult initializeResult = initializeExchange.result();
        String negotiatedVersion = validateNegotiatedVersion(initializeResult == null ? null : initializeResult.protocolVersion(), server.getProtocol());
        sendHttpNotification(
                client,
                server.getEndpointUrl(),
                negotiatedVersion,
                initializeExchange.sessionId(),
                buildRequest("notifications/initialized", Map.of(), true)
        );
        return invokeToolCallHttp(
                client,
                server.getEndpointUrl(),
                negotiatedVersion,
                initializeExchange.sessionId(),
                remoteToolName,
                jsonArguments
        );
    }

    private McpToolCallResult callToolOverLegacySse(McpServerDTO server, String remoteToolName, String jsonArguments) {
        try (LegacySseSession session = openLegacySseSession(server, buildWebClient(server))) {
            String preferredVersion = properties.getPreferredSseProtocolVersion();
            McpInitializeResult initializeResult = session.request(
                    buildRequest("initialize", buildInitializeParams(preferredVersion), false),
                    McpInitializeResult.class
            );
            validateNegotiatedVersion(initializeResult == null ? null : initializeResult.protocolVersion(), server.getProtocol());
            session.notify(buildRequest("notifications/initialized", Map.of(), true));
            return session.toolCall(remoteToolName, jsonArguments);
        }
    }

    private WebClient buildWebClient(McpServerDTO server) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.getConnectTimeoutMs()))
                .responseTimeout(Duration.ofMillis(properties.getResponseTimeoutMs()))
                .doOnConnected(connection -> connection
                        .addHandlerLast(new ReadTimeoutHandler(properties.getReadTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(properties.getWriteTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS)));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(org.springframework.web.reactive.function.client.ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(properties.getMaxInMemorySizeBytes()))
                        .build())
                .defaultHeaders(headers -> applyAuthHeaders(headers, server))
                .build();
    }

    private LegacySseSession openLegacySseSession(McpServerDTO server, WebClient client) {
        Sinks.One<String> postEndpointSink = Sinks.one();
        ConcurrentHashMap<String, Sinks.One<McpJsonRpcResponse>> pendingResponses = new ConcurrentHashMap<>();
        Disposable subscription = client.get()
                .uri(server.getEndpointUrl())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE_TYPE)
                .retryWhen(Retry.backoff(
                                Math.max(1, properties.getSseMaxReconnects()),
                                Duration.ofMillis(properties.getSseInitialBackoffMs())
                        ).maxBackoff(Duration.ofMillis(properties.getSseMaxBackoffMs())))
                .subscribe(
                        event -> handleSseEvent(server, postEndpointSink, pendingResponses, event),
                        error -> handleSseError(postEndpointSink, pendingResponses, mapTransportError(error))
                );
        String endpoint = postEndpointSink.asMono()
                .timeout(Duration.ofMillis(properties.getSseConnectTimeoutMs()))
                .onErrorMap(error -> new McpTransportException("MCP_SSE_ENDPOINT_TIMEOUT", "Timed out waiting for SSE endpoint announcement", error))
                .block();
        if (!StringUtils.hasText(endpoint)) {
            subscription.dispose();
            throw new McpTransportException("MCP_SSE_ENDPOINT_MISSING", "SSE transport never announced a POST endpoint");
        }
        return new LegacySseSession(server, client, postEndpointSink, pendingResponses, subscription);
    }

    private void applyAuthHeaders(HttpHeaders headers, McpServerDTO server) {
        String credentials = credentialCipher.decrypt(server.getEncryptedCredentials(), server.getCredentialKeyVersion());
        if (!StringUtils.hasText(credentials) || server.getAuthType() == null || server.getAuthType() == McpAuthType.NONE) {
            return;
        }
        switch (server.getAuthType()) {
            case API_KEY -> headers.set("X-API-Key", credentials.trim());
            case BEARER_TOKEN -> headers.setBearerAuth(credentials.trim());
            case OAUTH2_CLIENT -> throw new McpTransportException(
                    "MCP_AUTH_UNSUPPORTED",
                    "OAuth2 client credentials transport is not implemented yet"
            );
            default -> {
            }
        }
    }

    private Map<String, Object> buildRequest(String method, Object params, boolean notification) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("method", method);
        if (params != null) {
            payload.put("params", params);
        }
        if (!notification) {
            payload.put("id", UUID.randomUUID().toString());
        }
        return payload;
    }

    private Map<String, Object> buildInitializeParams(String protocolVersion) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", protocolVersion);
        params.put("capabilities", Map.of(
                "tools", Map.of("listChanged", false)
        ));
        params.put("clientInfo", Map.of(
                "name", properties.getClientName(),
                "version", properties.getClientVersion()
        ));
        return params;
    }

    private Map<String, Object> buildToolCallParams(String remoteToolName, String jsonArguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", remoteToolName);
        params.put("arguments", parseArguments(jsonArguments));
        return params;
    }

    private <T> HttpJsonRpcExchange<T> invokeHttpRequest(WebClient client,
                                                         String endpointUrl,
                                                         String protocolVersion,
                                                         String sessionId,
                                                         Map<String, Object> request,
                                                         Class<T> resultType,
                                                         String fallbackSessionId) {
        String requestId = stringifyId(objectMapper.valueToTree(request.get("id")));
        HttpResponseEnvelope response = client.post()
                .uri(endpointUrl)
                .headers(headers -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
                    if (StringUtils.hasText(protocolVersion)) {
                        headers.set(HEADER_PROTOCOL_VERSION, protocolVersion);
                    }
                    if (StringUtils.hasText(sessionId)) {
                        headers.set(HEADER_SESSION_ID, sessionId);
                    }
                })
                .bodyValue(request)
                .exchangeToMono(clientResponse -> extractResponse(clientResponse, requestId))
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .onErrorMap(this::mapTransportError)
                .block();
        T result = convertResponse(response == null ? null : response.payload(), resultType);
        String resolvedSessionId = response == null || !StringUtils.hasText(response.sessionId())
                ? fallbackSessionId
                : response.sessionId();
        return new HttpJsonRpcExchange<>(result, resolvedSessionId);
    }

    private McpToolCallResult invokeToolCallHttp(WebClient client,
                                                 String endpointUrl,
                                                 String protocolVersion,
                                                 String sessionId,
                                                 String remoteToolName,
                                                 String jsonArguments) {
        Map<String, Object> request = buildRequest("tools/call", buildToolCallParams(remoteToolName, jsonArguments), false);
        String requestId = stringifyId(objectMapper.valueToTree(request.get("id")));
        HttpResponseEnvelope response = client.post()
                .uri(endpointUrl)
                .headers(headers -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
                    if (StringUtils.hasText(protocolVersion)) {
                        headers.set(HEADER_PROTOCOL_VERSION, protocolVersion);
                    }
                    if (StringUtils.hasText(sessionId)) {
                        headers.set(HEADER_SESSION_ID, sessionId);
                    }
                })
                .bodyValue(request)
                .exchangeToMono(clientResponse -> extractResponse(clientResponse, requestId))
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .onErrorMap(this::mapTransportError)
                .block();
        return convertToolCallResponse(response == null ? null : response.payload());
    }

    private void sendHttpNotification(WebClient client,
                                      String endpointUrl,
                                      String protocolVersion,
                                      String sessionId,
                                      Map<String, Object> notification) {
        client.post()
                .uri(endpointUrl)
                .headers(headers -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
                    if (StringUtils.hasText(protocolVersion)) {
                        headers.set(HEADER_PROTOCOL_VERSION, protocolVersion);
                    }
                    if (StringUtils.hasText(sessionId)) {
                        headers.set(HEADER_SESSION_ID, sessionId);
                    }
                })
                .bodyValue(notification)
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new McpTransportException(
                                        "MCP_HTTP_" + response.statusCode().value(),
                                        "MCP server returned HTTP " + response.statusCode().value() + ": " + trimForError(body)
                                )));
                    }
                    return response.bodyToMono(Void.class);
                })
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .onErrorMap(this::mapTransportError)
                .block();
    }

    private Mono<HttpResponseEnvelope> extractResponse(ClientResponse response, String requestId) {
        if (!response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMap(body -> Mono.error(new McpTransportException(
                            "MCP_HTTP_" + response.statusCode().value(),
                            "MCP server returned HTTP " + response.statusCode().value() + ": " + trimForError(body)
                    )));
        }
        MediaType contentType = response.headers().contentType().orElse(MediaType.APPLICATION_JSON);
        String sessionId = response.headers().header(HEADER_SESSION_ID).stream()
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
        if (contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            return response.bodyToMono(String.class)
                    .map(this::parseJsonRpcResponse)
                    .map(payload -> new HttpResponseEnvelope(payload, sessionId))
                    .switchIfEmpty(Mono.error(new McpTransportException("MCP_EMPTY_RESPONSE", "MCP server returned an empty JSON-RPC response")));
        }
        if (contentType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)) {
            return response.bodyToFlux(SSE_TYPE)
                    .mapNotNull(ServerSentEvent::data)
                    .filter(StringUtils::hasText)
                    .map(this::parseJsonRpcResponse)
                    .filter(jsonRpcResponse -> requestId.equals(stringifyId(jsonRpcResponse.id())))
                    .map(payload -> new HttpResponseEnvelope(payload, sessionId))
                    .next()
                    .switchIfEmpty(Mono.error(new McpTransportException("MCP_EMPTY_SSE_RESPONSE", "MCP server closed the SSE response before replying")));
        }
        return response.bodyToMono(String.class)
                .flatMap(body -> Mono.error(new McpTransportException(
                        "MCP_UNSUPPORTED_CONTENT_TYPE",
                        "Unsupported MCP response content type: " + contentType + ", body=" + trimForError(body)
                )));
    }

    private <T> T convertResponse(McpJsonRpcResponse response, Class<T> resultType) {
        if (response == null) {
            throw new McpTransportException("MCP_EMPTY_RESPONSE", "MCP server returned no JSON-RPC payload");
        }
        if (response.error() != null) {
            throw new McpTransportException(
                    "MCP_REMOTE_ERROR_" + response.error().code(),
                    "Remote MCP error: " + response.error().message()
            );
        }
        if (response.result() == null || response.result().isNull()) {
            if (resultType == Void.class) {
                return null;
            }
            throw new McpTransportException("MCP_EMPTY_RESULT", "MCP response result is empty");
        }
        try {
            return objectMapper.treeToValue(response.result(), resultType);
        } catch (JsonProcessingException ex) {
            throw new McpTransportException("MCP_RESULT_DECODE_FAILED", "Failed to decode MCP result payload", ex);
        }
    }

    private McpToolCallResult convertToolCallResponse(McpJsonRpcResponse response) {
        if (response == null) {
            throw new McpTransportException("MCP_EMPTY_RESPONSE", "MCP server returned no JSON-RPC payload");
        }
        if (response.error() != null) {
            throw new McpTransportException(
                    "MCP_REMOTE_ERROR_" + response.error().code(),
                    "Remote MCP error: " + response.error().message()
            );
        }
        if (response.result() == null || response.result().isNull()) {
            return new McpToolCallResult("");
        }
        return new McpToolCallResult(serializeSchema(response.result()));
    }

    private String validateNegotiatedVersion(String protocolVersion, McpProtocol protocol) {
        if (!StringUtils.hasText(protocolVersion)) {
            throw new McpTransportException("MCP_PROTOCOL_VERSION_MISSING", "MCP server did not return protocolVersion from initialize");
        }
        String normalized = protocolVersion.trim();
        if (protocol == McpProtocol.SSE && !properties.getPreferredSseProtocolVersion().equals(normalized)) {
            throw new McpTransportException(
                    "MCP_PROTOCOL_VERSION_UNSUPPORTED",
                    "Legacy SSE transport only supports protocol version " + properties.getPreferredSseProtocolVersion()
            );
        }
        return normalized;
    }

    private McpDiscoveryResult toDiscovery(McpInitializeResult initializeResult,
                                           String negotiatedVersion,
                                           McpToolsListResult toolsListResult) {
        List<McpRemoteToolDescriptor> tools = new ArrayList<>();
        if (toolsListResult != null && toolsListResult.tools() != null) {
            for (McpRemoteTool tool : toolsListResult.tools()) {
                if (tool == null || !StringUtils.hasText(tool.name())) {
                    continue;
                }
                JsonNode inputSchema = tool.inputSchema() == null ? objectMapper.createObjectNode() : tool.inputSchema();
                tools.add(new McpRemoteToolDescriptor(
                        tool.name().trim(),
                        resolveDescription(tool),
                        serializeSchema(inputSchema),
                        sha256Hex(serializeSchema(inputSchema))
                ));
            }
        }
        return new McpDiscoveryResult(
                negotiatedVersion,
                initializeResult != null && initializeResult.serverInfo() != null ? initializeResult.serverInfo().name() : null,
                initializeResult != null && initializeResult.serverInfo() != null ? initializeResult.serverInfo().version() : null,
                LocalDateTime.now(),
                tools
        );
    }

    private String resolveDescription(McpRemoteTool tool) {
        if (tool == null) {
            return null;
        }
        if (StringUtils.hasText(tool.description())) {
            return tool.description().trim();
        }
        if (StringUtils.hasText(tool.title())) {
            return tool.title().trim();
        }
        return null;
    }

    private String serializeSchema(JsonNode inputSchema) {
        try {
            return objectMapper.writeValueAsString(inputSchema == null ? objectMapper.createObjectNode() : inputSchema);
        } catch (JsonProcessingException ex) {
            throw new McpTransportException("MCP_SCHEMA_SERIALIZE_FAILED", "Failed to serialize MCP input schema", ex);
        }
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 must be available", ex);
        }
    }

    private McpJsonRpcResponse parseJsonRpcResponse(String body) {
        try {
            return objectMapper.readValue(body, McpJsonRpcResponse.class);
        } catch (JsonProcessingException ex) {
            throw new McpTransportException("MCP_JSONRPC_DECODE_FAILED", "Failed to decode MCP JSON-RPC response: " + trimForError(body), ex);
        }
    }

    private JsonNode parseArguments(String jsonArguments) {
        if (!StringUtils.hasText(jsonArguments)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(jsonArguments);
        } catch (JsonProcessingException ex) {
            throw new McpTransportException("MCP_INVALID_TOOL_ARGUMENTS", "Failed to parse tool arguments as JSON", ex);
        }
    }

    private RuntimeException mapTransportError(Throwable error) {
        if (error instanceof McpTransportException transportException) {
            return transportException;
        }
        if (error instanceof java.util.concurrent.TimeoutException) {
            return new McpTransportException("MCP_TIMEOUT", "MCP request timed out", error);
        }
        if (error instanceof WebClientResponseException responseException) {
            return new McpTransportException(
                    "MCP_HTTP_" + responseException.getStatusCode().value(),
                    "MCP server returned HTTP " + responseException.getStatusCode().value() + ": " + trimForError(responseException.getResponseBodyAsString()),
                    error
            );
        }
        if (error instanceof WebClientRequestException) {
            return new McpTransportException("MCP_CONNECT_FAILED", "Failed to connect to MCP server: " + error.getMessage(), error);
        }
        return new McpTransportException("MCP_TRANSPORT_FAILED", error.getMessage(), error);
    }

    private String stringifyId(JsonNode idNode) {
        if (idNode == null || idNode.isNull()) {
            return null;
        }
        if (idNode.isTextual()) {
            return idNode.asText();
        }
        return idNode.toString();
    }

    private String trimForError(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        String normalized = body.trim().replaceAll("\\s+", " ");
        return normalized.length() > 240 ? normalized.substring(0, 240) + "..." : normalized;
    }

    private final class LegacySseSession implements AutoCloseable {

        private final McpServerDTO server;
        private final WebClient client;
        private final Sinks.One<String> postEndpointSink;
        private final ConcurrentHashMap<String, Sinks.One<McpJsonRpcResponse>> pendingResponses;
        private final Disposable subscription;

        private LegacySseSession(McpServerDTO server,
                                 WebClient client,
                                 Sinks.One<String> postEndpointSink,
                                 ConcurrentHashMap<String, Sinks.One<McpJsonRpcResponse>> pendingResponses,
                                 Disposable subscription) {
            this.server = server;
            this.client = client;
            this.postEndpointSink = postEndpointSink;
            this.pendingResponses = pendingResponses;
            this.subscription = subscription;
        }

        <T> T request(Map<String, Object> request, Class<T> resultType) {
            return convertResponse(requestRaw(request), resultType);
        }

        McpToolCallResult toolCall(String remoteToolName, String jsonArguments) {
            Map<String, Object> request = buildRequest("tools/call", buildToolCallParams(remoteToolName, jsonArguments), false);
            return convertToolCallResponse(requestRaw(request));
        }

        void notify(Map<String, Object> notification) {
            String postEndpoint = postEndpointSink.asMono().block();
            client.post()
                    .uri(postEndpoint)
                    .headers(headers -> {
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    })
                    .bodyValue(notification)
                    .exchangeToMono(response -> {
                        if (!response.statusCode().is2xxSuccessful()) {
                            return response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(body -> Mono.error(new McpTransportException(
                                            "MCP_HTTP_" + response.statusCode().value(),
                                            "MCP server returned HTTP " + response.statusCode().value() + ": " + trimForError(body)
                                    )));
                        }
                        return response.bodyToMono(Void.class);
                    })
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .onErrorMap(WebClientMcpTransportClient.this::mapTransportError)
                    .block();
        }

        @Override
        public void close() {
            if (subscription != null) {
                subscription.dispose();
            }
            pendingResponses.clear();
        }

        private McpJsonRpcResponse requestRaw(Map<String, Object> request) {
            String requestId = String.valueOf(request.get("id"));
            Sinks.One<McpJsonRpcResponse> responseSink = Sinks.one();
            pendingResponses.put(requestId, responseSink);
            try {
                String postEndpoint = postEndpointSink.asMono().block();
                McpJsonRpcResponse immediateResponse = client.post()
                        .uri(postEndpoint)
                        .headers(headers -> {
                            headers.setContentType(MediaType.APPLICATION_JSON);
                            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                        })
                        .bodyValue(request)
                        .exchangeToMono(response -> {
                            if (!response.statusCode().is2xxSuccessful()) {
                                return response.bodyToMono(String.class)
                                        .defaultIfEmpty("")
                                        .flatMap(body -> Mono.error(new McpTransportException(
                                                "MCP_HTTP_" + response.statusCode().value(),
                                                "MCP server returned HTTP " + response.statusCode().value() + ": " + trimForError(body)
                                        )));
                            }
                            return response.headers().contentType()
                                    .filter(contentType -> contentType.isCompatibleWith(MediaType.APPLICATION_JSON))
                                    .map(ignored -> response.bodyToMono(String.class).map(WebClientMcpTransportClient.this::parseJsonRpcResponse))
                                    .orElseGet(() -> response.bodyToMono(Void.class).then(Mono.empty()));
                        })
                        .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                        .onErrorMap(WebClientMcpTransportClient.this::mapTransportError)
                        .block();
                if (immediateResponse != null) {
                    return immediateResponse;
                }
                return responseSink.asMono()
                        .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                        .onErrorMap(error -> new McpTransportException("MCP_SSE_RESPONSE_TIMEOUT", "Timed out waiting for SSE response", error))
                        .block();
            } finally {
                pendingResponses.remove(requestId);
            }
        }
    }

    private record HttpResponseEnvelope(
            McpJsonRpcResponse payload,
            String sessionId
    ) {
    }

    private record HttpJsonRpcExchange<T>(
            T result,
            String sessionId
    ) {
    }

    private void handleSseEvent(McpServerDTO server,
                                Sinks.One<String> postEndpointSink,
                                ConcurrentHashMap<String, Sinks.One<McpJsonRpcResponse>> pendingResponses,
                                ServerSentEvent<String> event) {
        String eventType = event.event();
        String data = event.data();
        if (!StringUtils.hasText(data)) {
            return;
        }
        if ("endpoint".equalsIgnoreCase(eventType)) {
            URI resolved = URI.create(server.getEndpointUrl()).resolve(data.trim());
            postEndpointSink.tryEmitValue(resolved.toString());
            return;
        }
        McpJsonRpcResponse response;
        try {
            response = objectMapper.readValue(data, McpJsonRpcResponse.class);
        } catch (JsonProcessingException ignored) {
            return;
        }
        if (response.id() == null || response.id().isNull()) {
            return;
        }
        Sinks.One<McpJsonRpcResponse> sink = pendingResponses.get(stringifyId(response.id()));
        if (sink != null) {
            sink.tryEmitValue(response);
        }
    }

    private void handleSseError(Sinks.One<String> postEndpointSink,
                                ConcurrentHashMap<String, Sinks.One<McpJsonRpcResponse>> pendingResponses,
                                RuntimeException error) {
        postEndpointSink.tryEmitError(error);
        pendingResponses.values().forEach(sink -> sink.tryEmitError(error));
        pendingResponses.clear();
    }
}
