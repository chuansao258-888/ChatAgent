package com.yulong.chatagent.rag.parser;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class MinerUVdpEngineTest {

    @Test
    void shouldGroupContentListResultsByZeroBasedPageIndex() {
        MinerUVdpEngine engine = new MinerUVdpEngine(mock(WebClient.class), new MinerUProperties());

        List<VdpPageResult> results = engine.normalizePageResults(
                new MinerUVdpEngine.MinerUTaskResultEnvelope(
                        "task-1",
                        "completed",
                        null,
                        null,
                        Map.of(
                                "document",
                                new MinerUVdpEngine.MinerUFileResult(
                                        "ignored",
                                        List.of(
                                                Map.of("type", "text", "page_idx", 1, "text", "Second page"),
                                                Map.of("type", "text", "page_idx", 0, "text", "First page", "text_level", 2),
                                                Map.of("type", "table", "page_idx", 1, "table_body", "| A | B |")
                                        )
                                )
                        )
                )
        );

        assertThat(results).hasSize(2);
        assertThat(results.get(0).pageIndex()).isZero();
        assertThat(results.get(0).markdown()).isEqualTo("## First page");
        assertThat(results.get(1).pageIndex()).isEqualTo(1);
        assertThat(results.get(1).markdown()).contains("Second page").contains("| A | B |");
        assertThat(results.get(1).metadata()).containsEntry("visualType", "TABLE");
    }

    @Test
    void shouldReturnAllMappedPagesEvenWhenOnlySubsetWasRequested() {
        AtomicInteger statusCalls = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> respond(request.method(), request.url().getPath(), statusCalls))
                .build();
        MinerUProperties properties = new MinerUProperties();
        properties.setPollIntervalMs(1L);
        properties.setMaxPollAttempts(5);
        MinerUVdpEngine engine = new MinerUVdpEngine(webClient, properties);

        List<VdpPageResult> results = engine.parsePagesAsync(
                () -> new ByteArrayInputStream(new byte[]{1, 2, 3}),
                List.of(1),
                new VdpOptions(false, "en", null),
                Runnable::run
        ).join();

        assertThat(results).extracting(VdpPageResult::pageIndex).containsExactly(0, 1);
    }

    @Test
    void shouldFallbackToFileMarkdownWhenContentListMissing() {
        MinerUVdpEngine engine = new MinerUVdpEngine(mock(WebClient.class), new MinerUProperties());

        List<VdpPageResult> results = engine.normalizePageResults(
                new MinerUVdpEngine.MinerUTaskResultEnvelope(
                        "task-1",
                        "completed",
                        null,
                        null,
                        Map.of("document", new MinerUVdpEngine.MinerUFileResult("## Batch Heading", null))
                )
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).pageIndex()).isZero();
        assertThat(results.get(0).status()).isEqualTo(VdpPageStatus.SUCCESS);
        assertThat(results.get(0).markdown()).isEqualTo("## Batch Heading");
    }

    @Test
    void shouldRejectSinglePageParsingMode() {
        MinerUVdpEngine engine = new MinerUVdpEngine(mock(WebClient.class), new MinerUProperties());

        assertThatThrownBy(() -> engine.parsePage(() -> null, "png", new VdpOptions(false, null, null)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("batch PDF");
    }

    @Test
    void shouldAdvertiseSuggestedKnowledgeTimeoutAbovePollingWindow() {
        MinerUProperties properties = new MinerUProperties();
        properties.setSubmitTimeoutMs(30_000L);
        properties.setPollIntervalMs(2_000L);
        properties.setMaxPollAttempts(150);
        properties.setPollTimeoutMs(5_000L);
        MinerUVdpEngine engine = new MinerUVdpEngine(mock(WebClient.class), properties);

        assertThat(engine.suggestedDocumentTimeoutMs(PipelineSource.KNOWLEDGE)).isGreaterThanOrEqualTo(340_000L);
        assertThat(engine.suggestedDocumentTimeoutMs(PipelineSource.SESSION)).isZero();
    }

    @Test
    void shouldSubmitPollAndFetchBatchResultsOverOfficialMinerUHttpApi() {
        AtomicInteger statusCalls = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> respond(request.method(), request.url().getPath(), statusCalls))
                .build();
        MinerUProperties properties = new MinerUProperties();
        properties.setPollIntervalMs(1L);
        properties.setMaxPollAttempts(5);
        MinerUVdpEngine engine = new MinerUVdpEngine(webClient, properties);

        List<VdpPageResult> results = engine.parsePagesAsync(
                () -> new ByteArrayInputStream(new byte[]{1, 2, 3}),
                List.of(0),
                new VdpOptions(false, "en", null),
                Runnable::run
        ).join();

        assertThat(results).hasSize(2);
        assertThat(results.get(0).pageIndex()).isZero();
        assertThat(results.get(0).markdown()).contains("## Batch Heading").contains("| A | B |");
        assertThat(results.get(0).metadata()).containsEntry("visualType", "TABLE");
        assertThat(results.get(1).pageIndex()).isEqualTo(1);
        assertThat(results.get(1).markdown()).contains("Second page");
    }

    @Test
    void shouldRecordParsePagesLatencyMetric() {
        AtomicInteger statusCalls = new AtomicInteger();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> respond(request.method(), request.url().getPath(), statusCalls))
                .build();
        MinerUProperties properties = new MinerUProperties();
        properties.setPollIntervalMs(1L);
        properties.setMaxPollAttempts(5);
        MinerUVdpEngine engine = new MinerUVdpEngine(webClient, properties, meterRegistry);

        engine.parsePagesAsync(
                () -> new ByteArrayInputStream(new byte[]{1, 2, 3}),
                List.of(0),
                new VdpOptions(false, null, null),
                Runnable::run
        ).join();

        assertThat(meterRegistry.get("vdp.engine.parsePages.latency")
                .tags("engineId", "mineru", "status", "SUCCESS")
                .timer()
                .count()).isEqualTo(1L);
    }

    @Test
    void shouldRejectInvalidTaskIdReturnedByMinerU() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(jsonResponse("""
                        {"task_id":"bad task","status":"pending","status_url":"http://mineru/tasks/bad","result_url":"http://mineru/tasks/bad/result"}
                        """, HttpStatus.ACCEPTED)))
                .build();
        MinerUVdpEngine engine = new MinerUVdpEngine(webClient, new MinerUProperties());

        assertThatThrownBy(() -> engine.parsePagesAsync(
                () -> new ByteArrayInputStream(new byte[]{1}),
                List.of(0),
                new VdpOptions(false, null, null),
                Runnable::run
        ).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("MinerU returned invalid taskId: bad task");
    }

    @Test
    void shouldRespectConfiguredExecutorForAsyncDispatch() {
        AtomicInteger statusCalls = new AtomicInteger();
        AtomicInteger executorInvocations = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> respond(request.method(), request.url().getPath(), statusCalls))
                .build();
        MinerUVdpEngine engine = new MinerUVdpEngine(webClient, new MinerUProperties());

        List<VdpPageResult> results = engine.parsePagesAsync(
                () -> new ByteArrayInputStream(new byte[]{1}),
                List.of(0),
                new VdpOptions(false, null, null),
                command -> {
                    executorInvocations.incrementAndGet();
                    command.run();
                }
        ).join();

        assertThat(results).hasSize(2);
        assertThat(executorInvocations.get()).isPositive();
    }

    @Test
    void shouldAttachBearerAuthorizationHeaderWhenConfigured() {
        AtomicInteger statusCalls = new AtomicInteger();
        List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    authorizationHeaders.add(request.headers().getFirst(HttpHeaders.AUTHORIZATION));
                    return respond(request.method(), request.url().getPath(), statusCalls);
                });
        MinerUProperties properties = new MinerUProperties();
        properties.setBaseUrl("http://mineru.example");
        properties.setBearerToken("secret-token");
        properties.setPollIntervalMs(1L);
        properties.setMaxPollAttempts(5);
        MinerUVdpEngine engine = new MinerUVdpEngine(
                builder,
                properties,
                new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class)
        );

        List<VdpPageResult> results = engine.parsePagesAsync(
                () -> new ByteArrayInputStream(new byte[]{1}),
                List.of(0),
                new VdpOptions(false, null, null),
                Runnable::run
        ).join();

        assertThat(results).hasSize(2);
        assertThat(authorizationHeaders).isNotEmpty();
        assertThat(authorizationHeaders).allMatch("Bearer secret-token"::equals);
    }

    @Test
    void shouldAvoidOverflowWhenConfiguredMaxPdfSizeIsVeryLarge() {
        AtomicInteger statusCalls = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> respond(request.method(), request.url().getPath(), statusCalls))
                .build();
        MinerUProperties properties = new MinerUProperties();
        properties.setMaxPdfSizeMb(2048);
        properties.setPollIntervalMs(1L);
        properties.setMaxPollAttempts(5);
        MinerUVdpEngine engine = new MinerUVdpEngine(webClient, properties);

        List<VdpPageResult> results = engine.parsePagesAsync(
                () -> new ByteArrayInputStream(new byte[]{1, 2, 3}),
                List.of(0),
                new VdpOptions(false, null, null),
                Runnable::run
        ).join();

        assertThat(results).hasSize(2);
        assertThat(results.get(0).markdown()).contains("## Batch Heading");
    }

    private Mono<ClientResponse> respond(HttpMethod method, String path, AtomicInteger statusCalls) {
        if (method == HttpMethod.POST && "/tasks".equals(path)) {
            return Mono.just(jsonResponse("""
                    {"task_id":"task-123","status":"pending","status_url":"http://mineru/tasks/task-123","result_url":"http://mineru/tasks/task-123/result","file_names":["document"]}
                    """, HttpStatus.ACCEPTED));
        }
        if (method == HttpMethod.GET && "/tasks/task-123".equals(path)) {
            String status = statusCalls.getAndIncrement() == 0 ? "processing" : "completed";
            return Mono.just(jsonResponse("{\"task_id\":\"task-123\",\"status\":\"" + status + "\"}", HttpStatus.OK));
        }
        if (method == HttpMethod.GET && "/tasks/task-123/result".equals(path)) {
            return Mono.just(jsonResponse("""
                    {
                      "task_id":"task-123",
                      "status":"completed",
                      "results":{
                        "document":{
                          "md_content":"## Batch Heading\\n\\n| A | B |",
                          "content_list":[
                            {"type":"text","page_idx":0,"text":"Batch Heading","text_level":2},
                            {"type":"table","page_idx":0,"table_caption":["Table 1"],"table_body":"| A | B |"},
                            {"type":"text","page_idx":1,"text":"Second page"}
                          ]
                        }
                      }
                    }
                    """, HttpStatus.OK));
        }
        if (method == HttpMethod.DELETE && "/tasks/task-123".equals(path)) {
            return Mono.just(ClientResponse.create(HttpStatus.METHOD_NOT_ALLOWED).build());
        }
        return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
    }

    private static ClientResponse jsonResponse(String json, HttpStatus status) {
        return ClientResponse.create(status)
                .header("Content-Type", "application/json")
                .body(json)
                .build();
    }
}
