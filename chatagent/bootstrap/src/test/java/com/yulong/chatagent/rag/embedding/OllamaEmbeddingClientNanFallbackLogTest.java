package com.yulong.chatagent.rag.embedding;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the NaN fallback log privacy contract in
 * {@link OllamaEmbeddingClient#embed(String)}.
 *
 * <p>Verifies that when all three retry attempts return NaN vectors, the warn log
 * contains only structured metadata (model, inputLen, inputSha256, cumulativeFallbacks)
 * and never the original input text.
 */
class OllamaEmbeddingClientNanFallbackLogTest {

    private static final String SENSITIVE_INPUT = "SENSITIVE_INPUT_TEXT_THAT_MUST_NOT_LOG";

    private ListAppender<ILoggingEvent> listAppender;
    private Logger logbackLogger;

    @BeforeEach
    void attachLogAppender() {
        logbackLogger = (Logger) LoggerFactory.getLogger(OllamaEmbeddingClient.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logbackLogger.addAppender(listAppender);
    }

    @AfterEach
    void detachLogAppender() {
        if (listAppender != null && logbackLogger != null) {
            logbackLogger.detachAppender(listAppender);
        }
    }

    @Test
    void nanFallbackLog_doesNotContainOriginalInput() throws Exception {
        // --- Arrange: build a real client, then inject a mock WebClient ---
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(
                WebClient.builder(), "http://localhost:1", "bge-m3");

        // Create a NaN vector to simulate Ollama returning bad embeddings
        float[] nanVector = new float[1024];
        Arrays.fill(nanVector, Float.NaN);

        // Create EmbeddingResponse (private inner @Data class) via reflection
        Class<?> responseClass = Class.forName(
                "com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient$EmbeddingResponse");
        Constructor<?> ctor = responseClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object nanResponse = ctor.newInstance();
        responseClass.getMethod("setEmbedding", float[].class).invoke(nanResponse, nanVector);

        // Mock the WebClient chain
        WebClient mockWebClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec<?> headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(mockWebClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/api/embeddings")).thenReturn(bodySpec);
        doReturn(headersSpec).when(bodySpec).bodyValue(any());
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(Class.class))).thenReturn(Mono.just(nanResponse));

        // Inject the mock WebClient into the client via reflection
        Field webClientField = OllamaEmbeddingClient.class.getDeclaredField("webClient");
        webClientField.setAccessible(true);
        webClientField.set(client, mockWebClient);

        // --- Act ---
        float[] result = client.embed(SENSITIVE_INPUT);

        // --- Assert: zero vector returned ---
        assertThat(result).hasSize(1024);
        assertThat(result).containsOnly(0.0f);
        assertThat(client.nanFallbackCount()).isEqualTo(1);

        // --- Assert: exactly one WARN log emitted ---
        List<ILoggingEvent> warnEvents = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warnEvents).hasSize(1);

        ILoggingEvent warnEvent = warnEvents.get(0);
        String formatted = warnEvent.getFormattedMessage();

        // Must contain structured metadata fields
        assertThat(formatted).contains("inputLen=");
        assertThat(formatted).contains("inputSha256=");
        assertThat(formatted).contains("cumulativeFallbacks=");
        assertThat(formatted).contains("bge-m3");

        // Must NOT contain the original sensitive input text
        assertThat(formatted).doesNotContain("SENSITIVE_INPUT");

        // Also verify no log argument is the raw input string
        Object[] args = warnEvent.getArgumentArray();
        if (args != null) {
            assertThat(args).noneMatch(arg ->
                    arg instanceof String s && s.contains("SENSITIVE_INPUT"));
        }
    }

    @Test
    void nanEncodingHttp500_usesControlledFallback() throws Exception {
        OllamaEmbeddingClient client = clientWithEmbeddingMono(Mono.error(new RuntimeException(
                "Ollama HTTP 500 — {\"error\":\"failed to encode response: json: unsupported value: NaN\"}")));

        float[] result = client.embed(SENSITIVE_INPUT);

        assertThat(result).hasSize(1024);
        assertThat(result).containsOnly(0.0f);
        assertThat(client.nanFallbackCount()).isEqualTo(1);

        List<ILoggingEvent> warnEvents = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warnEvents).hasSize(1);
        assertThat(warnEvents.get(0).getFormattedMessage())
                .contains("inputLen=")
                .contains("inputSha256=")
                .doesNotContain("SENSITIVE_INPUT");
    }

    @Test
    void nonNanHttp500_isNotSilentlyFallbacked() throws Exception {
        OllamaEmbeddingClient client = clientWithEmbeddingMono(Mono.error(new RuntimeException(
                "Ollama HTTP 500 — {\"error\":\"model overloaded\"}")));

        assertThatThrownBy(() -> client.embed(SENSITIVE_INPUT))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Ollama HTTP 500")
                .hasMessageContaining("model overloaded");
        assertThat(client.nanFallbackCount()).isZero();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OllamaEmbeddingClient clientWithEmbeddingMono(Mono<?> mono) throws Exception {
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(
                WebClient.builder(), "http://localhost:1", "bge-m3");

        WebClient mockWebClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec<?> headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(mockWebClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/api/embeddings")).thenReturn(bodySpec);
        doReturn(headersSpec).when(bodySpec).bodyValue(any());
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(Class.class))).thenReturn((Mono) mono);

        Field webClientField = OllamaEmbeddingClient.class.getDeclaredField("webClient");
        webClientField.setAccessible(true);
        webClientField.set(client, mockWebClient);
        return client;
    }
}
