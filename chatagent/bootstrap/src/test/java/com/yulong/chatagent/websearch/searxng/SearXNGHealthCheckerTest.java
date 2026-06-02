package com.yulong.chatagent.websearch.searxng;

import com.yulong.chatagent.websearch.WebSearchProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SearXNGHealthCheckerTest {

    @Test
    void shouldSkipProbeWhenDisabled() {
        WebSearchProperties props = new WebSearchProperties();
        props.setEnabled(false);

        SearXNGHealthChecker checker = new SearXNGHealthChecker(props);
        checker.probe();

        assertThat(checker.isReachable()).isFalse();
    }

    @Test
    void shouldDetectUnreachable() {
        WebSearchProperties props = new WebSearchProperties();
        props.setEnabled(true);
        // Use a random high port that is very unlikely to be in use
        props.setSearxngBaseUrl("http://127.0.0.1:1");

        SearXNGHealthChecker checker = new SearXNGHealthChecker(props);
        checker.probe();

        assertThat(checker.isReachable()).isFalse();
    }

    @Test
    void shouldDetectReachableWhenSearXNGResponds() throws Exception {
        // Start a local HTTP server on a random available port
        AtomicReference<String> capturedQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            capturedQuery.set(exchange.getRequestURI().getQuery());

            String body = "{\"results\":[],\"suggestions\":[]}";
            byte[] bytes = body.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            WebSearchProperties props = new WebSearchProperties();
            props.setEnabled(true);
            props.setSearxngBaseUrl("http://127.0.0.1:" + port);

            SearXNGHealthChecker checker = new SearXNGHealthChecker(props);
            checker.probe();

            assertThat(checker.isReachable()).isTrue();

            // Verify probe URL contains expected parameters (assert in main thread)
            String query = capturedQuery.get();
            assertThat(query).contains("health_check");
            assertThat(query).contains("format=json");
        } finally {
            server.stop(0);
        }
    }
}
