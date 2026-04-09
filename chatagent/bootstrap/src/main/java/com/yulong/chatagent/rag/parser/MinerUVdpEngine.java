package com.yulong.chatagent.rag.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * MinerU-backed full-document batch parser that uploads the entire PDF and polls for completion.
 */
@Component
@ConditionalOnProperty(prefix = "chatagent.rag.vdp.mineru", name = "enabled", havingValue = "true")
@Slf4j
public class MinerUVdpEngine implements VdpEngine {

    private static final Pattern TASK_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    private final WebClient webClient;
    private final MinerUProperties properties;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    @Autowired
    public MinerUVdpEngine(WebClient.Builder builder,
                           MinerUProperties properties,
                           ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(buildWebClient(builder, properties), properties, meterRegistryProvider.getIfAvailable());
    }

    MinerUVdpEngine(WebClient webClient, MinerUProperties properties) {
        this(webClient, properties, null);
    }

    MinerUVdpEngine(WebClient webClient, MinerUProperties properties, MeterRegistry meterRegistry) {
        this.webClient = webClient;
        this.properties = properties == null ? new MinerUProperties() : properties;
        this.meterRegistry = meterRegistry;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<VdpPageResult> parsePages(Supplier<InputStream> pdfStream,
                                          List<Integer> pageIndices,
                                          VdpOptions options) {
        try {
            return parsePagesAsync(pdfStream, pageIndices, options, Runnable::run).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("MinerU batch parsing failed: " + cause.getMessage(), cause);
        }
    }

    @Override
    public CompletableFuture<List<VdpPageResult>> parsePagesAsync(Supplier<InputStream> pdfStream,
                                                                  List<Integer> pageIndices,
                                                                  VdpOptions options,
                                                                  Executor executor) {
        Scheduler scheduler = resolveScheduler(executor);
        return Mono.defer(() -> {
                    Timer.Sample sample = VdpMetricsSupport.start(meterRegistry);
                    return submitTaskReactive(pdfStream, options)
                            .flatMap(submitResponse -> pollUntilCompleteReactive(submitResponse)
                                    .map(this::normalizePageResults)
                                    .onErrorResume(error -> cancelTaskReactive(submitResponse.taskId())
                                            .onErrorResume(cancelError -> {
                                                log.warn("Failed to cancel MinerU task {} after parsing error: {}", submitResponse.taskId(), cancelError.getMessage());
                                                return Mono.empty();
                                            })
                                            .then(Mono.error(error)))
                                    .doFinally(signalType -> {
                                        if (signalType == SignalType.CANCEL) {
                                            cancelTaskQuietly(submitResponse.taskId());
                                        }
                                    }))
                            .doOnSuccess(results -> VdpMetricsSupport.stop(
                                    meterRegistry,
                                    sample,
                                    "vdp.engine.parsePages.latency",
                                    "engineId",
                                    engineId(),
                                    "status",
                                    VdpMetricsSupport.aggregateStatus(results, null)
                            ))
                            .doOnError(error -> VdpMetricsSupport.stop(
                                    meterRegistry,
                                    sample,
                                    "vdp.engine.parsePages.latency",
                                    "engineId",
                                    engineId(),
                                    "status",
                                    VdpMetricsSupport.aggregateStatus(null, error)
                            ))
                            .doOnCancel(() -> VdpMetricsSupport.stop(
                                    meterRegistry,
                                    sample,
                                    "vdp.engine.parsePages.latency",
                                    "engineId",
                                    engineId(),
                                    "status",
                                    "FAILED"
                            ));
                })
                .subscribeOn(scheduler)
                .toFuture();
    }

    @Override
    public VdpPageResult parsePage(Supplier<InputStream> imageStream,
                                   String imageFormat,
                                   VdpOptions options) {
        throw new UnsupportedOperationException("MinerU only supports batch PDF parsing");
    }

    @Override
    public String engineId() {
        return "mineru";
    }

    @Override
    public String promptVersion() {
        return properties.getVersion();
    }

    @Override
    public EnumSet<VdpMode> supportedModes() {
        return EnumSet.of(VdpMode.PDF_PAGE_BATCH);
    }

    @Override
    public long suggestedDocumentTimeoutMs(PipelineSource pipelineSource) {
        if (pipelineSource != PipelineSource.KNOWLEDGE) {
            return 0L;
        }
        long pollWindowMs = Math.max(1L, properties.getMaxPollAttempts()) * Math.max(100L, properties.getPollIntervalMs());
        long fetchBudgetMs = Math.max(1000L, properties.getPollTimeoutMs()) * 2L;
        return Math.max(0L, properties.getSubmitTimeoutMs()) + pollWindowMs + fetchBudgetMs;
    }

    List<VdpPageResult> normalizePageResults(MinerUTaskResultEnvelope taskResult) {
        if (taskResult == null || taskResult.results() == null || taskResult.results().isEmpty()) {
            return List.of();
        }
        List<VdpPageResult> pageResults = new ArrayList<>();
        for (MinerUFileResult fileResult : taskResult.results().values()) {
            pageResults.addAll(toPageResults(fileResult));
        }
        return pageResults.stream()
                .sorted(Comparator.comparingInt(VdpPageResult::pageIndex))
                .toList();
    }

    private List<VdpPageResult> toPageResults(MinerUFileResult fileResult) {
        if (fileResult == null) {
            return List.of();
        }
        LinkedHashMap<Integer, StringBuilder> markdownByPage = new LinkedHashMap<>();
        LinkedHashMap<Integer, String> visualTypeByPage = new LinkedHashMap<>();
        List<Map<String, Object>> contentList = normalizeContentList(fileResult.contentList());
        if (contentList != null) {
            for (Map<String, Object> item : contentList) {
                if (item == null || item.isEmpty()) {
                    continue;
                }
                int pageIndex = intValue(item.get("page_idx"), 0);
                String rendered = renderContentListItem(item);
                if (StringUtils.hasText(rendered)) {
                    markdownByPage.computeIfAbsent(pageIndex, ignored -> new StringBuilder());
                    StringBuilder pageMarkdown = markdownByPage.get(pageIndex);
                    if (pageMarkdown.length() > 0) {
                        pageMarkdown.append("\n\n");
                    }
                    pageMarkdown.append(rendered.trim());
                }
                String inferredVisualType = inferVisualType(item);
                if (StringUtils.hasText(inferredVisualType)) {
                    String existing = visualTypeByPage.get(pageIndex);
                    if (!StringUtils.hasText(existing) || "IMAGE".equals(existing)) {
                        visualTypeByPage.put(pageIndex, inferredVisualType);
                    }
                }
            }
        }
        if (!markdownByPage.isEmpty()) {
            List<VdpPageResult> results = new ArrayList<>(markdownByPage.size());
            markdownByPage.forEach((pageIndex, markdownBuilder) -> {
                String markdown = markdownBuilder.toString().trim();
                String visualType = normalizeVisualType(visualTypeByPage.get(pageIndex));
                Map<String, Object> metadata = metadataFor(visualType, !StringUtils.hasText(markdown), null);
                results.add(new VdpPageResult(
                        pageIndex,
                        markdown,
                        StringUtils.hasText(markdown) ? VdpPageStatus.SUCCESS : VdpPageStatus.DEGRADED,
                        metadata
                ));
            });
            return results;
        }
        String markdown = trimToNull(fileResult.markdown());
        if (!StringUtils.hasText(markdown)) {
            return List.of();
        }
        return List.of(new VdpPageResult(
                0,
                markdown,
                VdpPageStatus.SUCCESS,
                metadataFor("IMAGE", false, null)
        ));
    }

    private Map<String, Object> metadataFor(String visualType, boolean degraded, String interpretiveNote) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("engineId", engineId());
        metadata.put("promptVersion", promptVersion());
        metadata.put("modelId", engineId());
        metadata.put("contentOrigin", "VDP_TRANSCRIBED");
        metadata.put("visualType", normalizeVisualType(visualType));
        metadata.put("degraded", degraded);
        if (StringUtils.hasText(interpretiveNote)) {
            metadata.put("interpretiveNote", interpretiveNote.trim());
        }
        return metadata;
    }

    private Mono<MinerUSubmitResponse> submitTaskReactive(Supplier<InputStream> pdfStream, VdpOptions options) {
        return Mono.fromCallable(() -> readPdfBytes(pdfStream))
                .flatMap(pdfBytes -> {
                    MultipartBodyBuilder builder = new MultipartBodyBuilder();
                    builder.part("files", new ByteArrayResource(pdfBytes) {
                                @Override
                                public String getFilename() {
                                    return "document.pdf";
                                }
                            })
                            .filename("document.pdf")
                            .contentType(MediaType.APPLICATION_PDF);
                    for (String language : resolveLangList(options)) {
                        builder.part("lang_list", language);
                    }
                    builder.part("backend", properties.getBackend());
                    builder.part("parse_method", properties.getParseMethod());
                    builder.part("formula_enable", Boolean.toString(options != null && options.recognizeFormulas()));
                    builder.part("table_enable", Boolean.toString(properties.isTableEnable()));
                    builder.part("return_md", "true");
                    builder.part("return_middle_json", "false");
                    builder.part("return_model_output", "false");
                    builder.part("return_content_list", "true");
                    builder.part("return_images", "false");
                    builder.part("response_format_zip", "false");
                    builder.part("return_original_file", "false");
                    builder.part("start_page_id", "0");
                    builder.part("end_page_id", "99999");
                    return webClient.post()
                            .uri("/tasks")
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .body(BodyInserters.fromMultipartData(builder.build()))
                            .retrieve()
                            .bodyToMono(MinerUSubmitResponse.class)
                            .timeout(Duration.ofMillis(properties.getSubmitTimeoutMs()))
                            .map(this::validateSubmitResponse)
                            .doFinally(signalType -> wipeBytes(pdfBytes));
                })
                .onErrorMap(error -> error instanceof IllegalStateException
                        ? error
                        : new IllegalStateException("MinerU task submission failed: " + error.getMessage(), error));
    }

    private Mono<MinerUTaskResultEnvelope> pollUntilCompleteReactive(MinerUSubmitResponse submitResponse) {
        return pollUntilCompleteReactive(submitResponse, 0);
    }

    private Mono<MinerUTaskResultEnvelope> pollUntilCompleteReactive(MinerUSubmitResponse submitResponse, int attempt) {
        if (attempt >= Math.max(1, properties.getMaxPollAttempts())) {
            return Mono.error(new IllegalStateException(
                    "MinerU poll timeout after " + properties.getMaxPollAttempts() + " attempts"
            ));
        }
        Mono<Void> waitSignal = attempt == 0
                ? Mono.empty()
                : Mono.delay(Duration.ofMillis(Math.max(100L, properties.getPollIntervalMs()))).then();
        return waitSignal.then(queryStatusReactive(submitResponse))
                .flatMap(statusResponse -> {
                    String status = normalizeTaskStatus(statusResponse == null ? null : statusResponse.status());
                    if ("completed".equals(status)) {
                        return fetchResultReactive(submitResponse);
                    }
                    if ("failed".equals(status) || "cancelled".equals(status)) {
                        String detail = resolveTaskMessage(statusResponse == null ? null : statusResponse.error(), statusResponse == null ? null : statusResponse.message());
                        return Mono.error(new IllegalStateException("MinerU task failed: " + detail));
                    }
                    return pollUntilCompleteReactive(submitResponse, attempt + 1);
                });
    }

    private Mono<MinerUTaskStatusResponse> queryStatusReactive(MinerUSubmitResponse submitResponse) {
        long perAttemptTimeoutMs = Math.max(500L, properties.getPollTimeoutMs());
        Mono<MinerUTaskStatusResponse> request = StringUtils.hasText(submitResponse.statusUrl())
                ? webClient.get().uri(submitResponse.statusUrl()).retrieve().bodyToMono(MinerUTaskStatusResponse.class)
                : webClient.get().uri("/tasks/{taskId}", validateTaskId(submitResponse.taskId())).retrieve().bodyToMono(MinerUTaskStatusResponse.class);
        return request
                .timeout(Duration.ofMillis(perAttemptTimeoutMs))
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .filter(this::isRetryablePollingError));
    }

    private Mono<MinerUTaskResultEnvelope> fetchResultReactive(MinerUSubmitResponse submitResponse) {
        Mono<MinerUTaskResultEnvelope> request = StringUtils.hasText(submitResponse.resultUrl())
                ? webClient.get().uri(submitResponse.resultUrl()).retrieve().bodyToMono(MinerUTaskResultEnvelope.class)
                : webClient.get().uri("/tasks/{taskId}/result", validateTaskId(submitResponse.taskId())).retrieve().bodyToMono(MinerUTaskResultEnvelope.class);
        return request
                .timeout(Duration.ofMillis(Math.max(1_000L, properties.getPollTimeoutMs())))
                .switchIfEmpty(Mono.error(new IllegalStateException("MinerU returned empty task result for " + submitResponse.taskId())))
                .flatMap(result -> {
                    String status = normalizeTaskStatus(result == null ? null : result.status());
                    if (result != null && result.results() != null && !result.results().isEmpty()) {
                        return Mono.just(result);
                    }
                    String detail = resolveTaskMessage(result == null ? null : result.error(), result == null ? null : result.message());
                    return Mono.error(new IllegalStateException("MinerU task result unavailable: " + detail));
                });
    }

    private Mono<Void> cancelTaskReactive(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return Mono.empty();
        }
        String normalizedTaskId = validateTaskId(taskId);
        return webClient.delete()
                .uri("/tasks/{taskId}", normalizedTaskId)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(5))
                .then()
                .onErrorResume(WebClientResponseException.class, error -> {
                    int status = error.getStatusCode().value();
                    return status == 404 || status == 405 ? Mono.empty() : Mono.error(error);
                });
    }

    private void cancelTaskQuietly(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        try {
            cancelTaskReactive(taskId).subscribe(
                    ignored -> { },
                    error -> log.warn("Failed to cancel MinerU task {}: {}", taskId, error.getMessage())
            );
        } catch (Exception e) {
            log.warn("Failed to cancel MinerU task {}: {}", taskId, e.getMessage());
        }
    }

    private boolean isRetryablePollingError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WebClientRequestException) {
                return true;
            }
            if (current instanceof WebClientResponseException responseException) {
                int status = responseException.getStatusCode().value();
                return status == 408 || status == 429 || status == 502 || status == 503 || status == 504;
            }
            if (current instanceof java.util.concurrent.TimeoutException || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private List<String> resolveLangList(VdpOptions options) {
        String languageHint = options == null ? null : options.languageHint();
        if (!StringUtils.hasText(languageHint)) {
            return List.of("ch");
        }
        String[] tokens = languageHint.split("[,;\\s]+");
        List<String> languages = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            if (StringUtils.hasText(token)) {
                languages.add(token.trim().toLowerCase(Locale.ROOT));
            }
        }
        return languages.isEmpty() ? List.of("ch") : languages;
    }

    private String renderContentListItem(Map<String, Object> item) {
        String type = normalizeContentType(stringValue(item.get("type")));
        return switch (type) {
            case "text", "header", "footer", "page_number", "aside_text", "page_footnote" -> renderTextContent(item);
            case "list", "ref_text" -> renderListContent(item);
            case "table" -> joinSections(
                    stringList(item.get("table_caption")),
                    trimToNull(stringValue(item.get("table_body"))),
                    stringList(item.get("table_footnote"))
            );
            case "image" -> joinSections(
                    stringList(item.get("image_caption")),
                    trimToNull(stringValue(item.get("text"))),
                    stringList(item.get("image_footnote"))
            );
            case "chart" -> joinSections(
                    stringList(item.get("chart_caption")),
                    trimToNull(firstNonBlank(stringValue(item.get("content")), stringValue(item.get("text")))),
                    stringList(item.get("chart_footnote"))
            );
            case "equation", "interline_equation" -> trimToNull(firstNonBlank(stringValue(item.get("text")), stringValue(item.get("content"))));
            case "code" -> renderCodeContent(item);
            default -> trimToNull(firstNonBlank(renderTextContent(item), stringValue(item.get("content"))));
        };
    }

    private String renderTextContent(Map<String, Object> item) {
        String text = trimToNull(stringValue(item.get("text")));
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Integer textLevel = nullableIntValue(item.get("text_level"));
        if (textLevel != null && textLevel > 0) {
            return "#".repeat(Math.min(6, textLevel)) + " " + text;
        }
        return text;
    }

    private String renderListContent(Map<String, Object> item) {
        List<String> listItems = stringList(item.get("list_items"));
        if (!listItems.isEmpty()) {
            return listItems.stream()
                    .filter(StringUtils::hasText)
                    .map(text -> "- " + text)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse(null);
        }
        return renderTextContent(item);
    }

    private String renderCodeContent(Map<String, Object> item) {
        String codeBody = trimToNull(stringValue(item.get("code_body")));
        String fencedCode = StringUtils.hasText(codeBody) ? "```\n" + codeBody + "\n```" : null;
        return joinSections(
                stringList(item.get("code_caption")),
                fencedCode,
                stringList(item.get("code_footnote"))
        );
    }

    private String inferVisualType(Map<String, Object> item) {
        String type = normalizeContentType(stringValue(item.get("type")));
        return switch (type) {
            case "table" -> "TABLE";
            case "chart" -> "CHART";
            case "equation", "interline_equation" -> "FORMULA";
            case "image", "seal" -> "IMAGE";
            default -> "IMAGE";
        };
    }

    private String joinSections(List<String> leadingSections, String body, List<String> trailingSections) {
        List<String> sections = new ArrayList<>();
        sections.addAll(leadingSections);
        if (StringUtils.hasText(body)) {
            sections.add(body.trim());
        }
        sections.addAll(trailingSections);
        return joinSections(sections);
    }

    private String joinSections(List<String> sections) {
        StringBuilder builder = new StringBuilder();
        for (String section : sections) {
            if (!StringUtils.hasText(section)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(section.trim());
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private String normalizeVisualType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "IMAGE";
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "TABLE", "CHART", "FORMULA", "IMAGE" -> normalized;
            default -> "IMAGE";
        };
    }

    private String normalizeContentType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private MinerUSubmitResponse validateSubmitResponse(MinerUSubmitResponse response) {
        if (response == null) {
            throw new IllegalStateException("MinerU task submission returned no payload");
        }
        validateTaskId(response.taskId());
        return response;
    }

    private String validateTaskId(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            throw new IllegalStateException("MinerU task submission returned no taskId");
        }
        String normalizedTaskId = taskId.trim();
        if (!TASK_ID_PATTERN.matcher(normalizedTaskId).matches()) {
            throw new IllegalStateException("MinerU returned invalid taskId: " + normalizedTaskId);
        }
        return normalizedTaskId;
    }

    private String normalizeTaskStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : "unknown";
    }

    private String resolveTaskMessage(String error, String message) {
        if (StringUtils.hasText(error)) {
            return error.trim();
        }
        if (StringUtils.hasText(message)) {
            return message.trim();
        }
        return "unknown MinerU task state";
    }

    private byte[] readPdfBytes(Supplier<InputStream> pdfStream) {
        long configuredMaxBytes = (long) Math.max(1, properties.getMaxPdfSizeMb()) * 1024L * 1024L;
        long maxBytes = Math.min(configuredMaxBytes, Integer.MAX_VALUE - 8L);
        try (InputStream stream = pdfStream.get();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            if (stream == null) {
                throw new IllegalStateException("MinerU pdfStream supplier returned null");
            }
            byte[] buffer = new byte[8192];
            long totalBytes = 0L;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                totalBytes += read;
                if (totalBytes > maxBytes) {
                    throw new IllegalStateException("MinerU PDF exceeds max size of " + properties.getMaxPdfSizeMb() + "MB");
                }
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("MinerU task submission failed: " + e.getMessage(), e);
        }
    }

    private void wipeBytes(byte[] bytes) {
        if (bytes == null) {
            return;
        }
        java.util.Arrays.fill(bytes, (byte) 0);
    }

    private Scheduler resolveScheduler(Executor executor) {
        return executor == null ? Schedulers.immediate() : Schedulers.fromExecutor(executor);
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private Integer nullableIntValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int intValue(Object value, int fallback) {
        Integer resolved = nullableIntValue(value);
        return resolved == null ? fallback : resolved;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> values) {
            List<String> result = new ArrayList<>(values.size());
            for (Object entry : values) {
                String text = trimToNull(stringValue(entry));
                if (StringUtils.hasText(text)) {
                    result.add(text);
                }
            }
            return result;
        }
        String text = trimToNull(stringValue(value));
        return StringUtils.hasText(text) ? List.of(text) : List.of();
    }

    private List<Map<String, Object>> normalizeContentList(Object rawContentList) {
        if (rawContentList instanceof List<?> values) {
            List<Map<String, Object>> result = new ArrayList<>(values.size());
            for (Object value : values) {
                if (value instanceof Map<?, ?> entry) {
                    Map<String, Object> normalizedEntry = new LinkedHashMap<>();
                    entry.forEach((key, itemValue) -> normalizedEntry.put(String.valueOf(key), itemValue));
                    result.add(normalizedEntry);
                }
            }
            return result;
        }
        if (rawContentList instanceof String json && StringUtils.hasText(json)) {
            try {
                return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
                });
            } catch (Exception e) {
                throw new IllegalStateException("MinerU returned invalid content_list payload: " + e.getMessage(), e);
            }
        }
        return null;
    }

    private static WebClient buildWebClient(WebClient.Builder builder, MinerUProperties properties) {
        MinerUProperties resolvedProperties = properties == null ? new MinerUProperties() : properties;
        WebClient.Builder configuredBuilder = builder
                .baseUrl(resolvedProperties.getBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (StringUtils.hasText(resolvedProperties.getBearerToken())) {
            configuredBuilder.defaultHeader(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + resolvedProperties.getBearerToken().trim()
            );
        }
        return configuredBuilder.build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MinerUSubmitResponse(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("status_url") String statusUrl,
            @JsonProperty("result_url") String resultUrl,
            @JsonProperty("file_names") List<String> fileNames,
            String status,
            String message,
            String error
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MinerUTaskStatusResponse(
            @JsonProperty("task_id") String taskId,
            String status,
            String message,
            String error,
            @JsonProperty("queued_ahead") Integer queuedAhead
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MinerUTaskResultEnvelope(
            @JsonProperty("task_id") String taskId,
            String status,
            String message,
            String error,
            Map<String, MinerUFileResult> results
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MinerUFileResult(
            @JsonProperty("md_content") String markdown,
            @JsonProperty("content_list") Object contentList
    ) {
    }
}
