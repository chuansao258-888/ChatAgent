package com.yulong.chatagent.websearch.brave;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yulong.chatagent.websearch.WebSearchProperties;
import com.yulong.chatagent.websearch.WebSearchRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BraveLlmContextClientTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void postsDocumentedFieldsAndMapsGroundingWithPublicSources() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/context", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"grounding":{"generic":[
                      {"url":"https://example.com/a","title":"Example","snippets":["one","two"]},
                      {"url":"http://127.0.0.1/private","title":"Private","snippets":["secret"]}
                    ]},"sources":{"https://example.com/a":{"title":"Example"}}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        WebSearchProperties properties = properties();
        BraveLlmContextClient client = new BraveLlmContextClient(properties, new ObjectMapper());
        var response = client.search(WebSearchRequest.validate(
                "release notes", 2, "WEEK", null, 3, 300, 3));

        assertThat(token.get()).isEqualTo("test-secret");
        JsonNode sent = new ObjectMapper().readTree(requestBody.get());
        assertThat(sent.path("freshness").asText()).isEqualTo("pw");
        assertThat(sent.path("context_threshold_mode").asText()).isEqualTo("balanced");
        assertThat(sent.path("enable_local").asBoolean()).isFalse();
        assertThat(sent.has("safe_search")).isFalse();
        assertThat(response.success()).isTrue();
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).snippet()).contains("one", "two");
    }

    @Test
    void mapsAuthenticationAndMalformedResponsesToStableOutcomes() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/context", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        BraveLlmContextClient client = new BraveLlmContextClient(properties(), new ObjectMapper());
        var response = client.search(WebSearchRequest.validate(
                "q", 1, "ANY", null, 3, 300, 3));
        assertThat(response.success()).isFalse();
        assertThat(response.errorMessage()).isEqualTo("AUTH_FAILURE");
    }

    private WebSearchProperties properties() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setBraveApiKey("test-secret");
        properties.setBraveBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/context");
        return properties;
    }
}
