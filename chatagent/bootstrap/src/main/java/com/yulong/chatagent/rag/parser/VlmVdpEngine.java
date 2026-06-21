package com.yulong.chatagent.rag.parser;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.chat.ChatModelRouter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Single-image VLM engine used by session uploads during the first multimodal rollout phase.
 */
@Component
@ConditionalOnProperty(prefix = "chatagent.rag.vdp.vlm", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class VlmVdpEngine implements VdpEngine {

    private final PromptLoader promptLoader;
    private final ChatModelRouter chatModelRouter;
    private final ObjectMapper objectMapper;
    private final VlmVdpProperties properties;
    private final VdpResultCacheService vdpResultCacheService;
    private final Executor vdpExecutor;
    private final MeterRegistry meterRegistry;

    @Autowired
    public VlmVdpEngine(PromptLoader promptLoader,
                        ChatModelRouter chatModelRouter,
                        ObjectMapper objectMapper,
                        VlmVdpProperties properties,
                        VdpResultCacheService vdpResultCacheService,
                        @Qualifier("vdpExecutor") Executor vdpExecutor,
                        ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(promptLoader, chatModelRouter, objectMapper, properties, vdpResultCacheService, vdpExecutor, meterRegistryProvider.getIfAvailable());
    }

    VlmVdpEngine(PromptLoader promptLoader,
                 ChatModelRouter chatModelRouter,
                 ObjectMapper objectMapper,
                 VlmVdpProperties properties,
                 VdpResultCacheService vdpResultCacheService,
                 Executor vdpExecutor) {
        this(promptLoader, chatModelRouter, objectMapper, properties, vdpResultCacheService, vdpExecutor, (MeterRegistry) null);
    }

    VlmVdpEngine(PromptLoader promptLoader,
                 ChatModelRouter chatModelRouter,
                 ObjectMapper objectMapper,
                 VlmVdpProperties properties,
                 VdpResultCacheService vdpResultCacheService,
                 Executor vdpExecutor,
                 MeterRegistry meterRegistry) {
        this.promptLoader = promptLoader;
        this.chatModelRouter = chatModelRouter;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.vdpResultCacheService = vdpResultCacheService;
        this.vdpExecutor = vdpExecutor;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public VdpPageResult parsePage(Supplier<InputStream> imageStream, String imageFormat, VdpOptions options) {
        Timer.Sample sample = VdpMetricsSupport.start(meterRegistry);
        String statusTag = "FAILED";
        byte[] imageBytes = null;
        boolean bytesOwnedByWorker = false;
        try {
            if (!properties.isEnabled()) {
                VdpPageResult result = degradedResult("VLM visual parsing is disabled");
                statusTag = VdpMetricsSupport.pageStatusTag(result);
                return result;
            }
            try (InputStream stream = imageStream.get()) {
                if (stream == null) {
                    VdpPageResult result = degradedResult("imageStream supplier returned null");
                    statusTag = VdpMetricsSupport.pageStatusTag(result);
                    return result;
                }
                // TODO Phase 5b: Spring AI Media 1.1.0 still materializes Resource payloads into byte[],
                // so move this path to provider-safe base64/remote media instead of heap materialization.
                imageBytes = stream.readAllBytes();
            }
            CacheLookupContext cacheLookupContext = buildCacheLookupContext(imageBytes, options);
            VdpPageResult cached = vdpResultCacheService.get(
                    cacheLookupContext.pipelineSource(),
                    engineId(),
                    promptVersion(),
                    cacheLookupContext.contentDigest(),
                    cacheLookupContext.sessionId()
            );
            if (cached != null) {
                wipeBytes(imageBytes);
                statusTag = VdpMetricsSupport.pageStatusTag(cached);
                return cached;
            }

            CompletableFuture<String> future = null;
            String response;
            try {
                future = submitPromptTask(imageBytes, imageFormat, options);
                bytesOwnedByWorker = true;
                response = future.get(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (future != null) {
                    future.cancel(true);
                }
                log.warn("VDP model call timed out after {} ms", properties.getTimeoutMs());
                VdpPageResult result = cacheAndReturn(
                        cacheLookupContext,
                        degradedResult("VLM timed out after " + properties.getTimeoutMs() + " ms")
                );
                statusTag = VdpMetricsSupport.pageStatusTag(result);
                return result;
            } catch (RejectedExecutionException e) {
                wipeBytes(imageBytes);
                imageBytes = null;
                log.warn("VDP executor rejected task: {}", e.getMessage());
                VdpPageResult result = cacheAndReturn(cacheLookupContext, degradedResult("VDP executor is saturated"));
                statusTag = VdpMetricsSupport.pageStatusTag(result);
                return result;
            } catch (ExecutionException e) {
                if (future != null) {
                    future.cancel(true);
                }
                Throwable cause = e.getCause() == null ? e : e.getCause();
                log.warn("VDP model call failed: {}", cause.getMessage());
                VdpPageResult result = cacheAndReturn(cacheLookupContext, degradedResult("VLM request failed: " + cause.getMessage()));
                statusTag = VdpMetricsSupport.pageStatusTag(result);
                return result;
            } catch (InterruptedException e) {
                if (future != null) {
                    future.cancel(true);
                }
                Thread.currentThread().interrupt();
                VdpPageResult result = cacheAndReturn(cacheLookupContext, degradedResult("VDP call interrupted"));
                statusTag = VdpMetricsSupport.pageStatusTag(result);
                return result;
            }

            if (!StringUtils.hasText(response)) {
                VdpPageResult result = cacheAndReturn(cacheLookupContext, degradedResult("VLM visual parser returned blank content"));
                statusTag = VdpMetricsSupport.pageStatusTag(result);
                return result;
            }

            VdpPageResult result = cacheAndReturn(cacheLookupContext, mapResponse(response.trim()));
            statusTag = VdpMetricsSupport.pageStatusTag(result);
            return result;
        } catch (Exception e) {
            if (!bytesOwnedByWorker) {
                wipeBytes(imageBytes);
            }
            VdpPageResult result = degradedResult("Failed to read image stream: " + e.getMessage());
            statusTag = VdpMetricsSupport.pageStatusTag(result);
            return result;
        } finally {
            VdpMetricsSupport.stop(
                    meterRegistry,
                    sample,
                    "vdp.engine.parsePage.latency",
                    "engineId",
                    engineId(),
                    "status",
                    statusTag
            );
        }
    }

    @Override
    public EnumSet<VdpMode> supportedModes() {
        return EnumSet.of(VdpMode.PAGE_IMAGE);
    }

    @Override
    public String engineId() {
        return "vlm";
    }

    @Override
    public String promptVersion() {
        return properties.getPromptVersion();
    }

    private String buildPrompt(VdpOptions options) {
        String languageHint = options == null || !StringUtils.hasText(options.languageHint())
                ? "auto"
                : options.languageHint().trim();
        return promptLoader.render(PromptConstants.VLM_PARSE, Map.of(
                "languageHint", languageHint
        ));
    }

    private String executePrompt(Prompt prompt) {
        return chatModelRouter.route(properties.getClientId())
                .prompt(prompt)
                .call()
                .content();
    }

    private CompletableFuture<String> submitPromptTask(byte[] imageBytes, String imageFormat, VdpOptions options) {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            vdpExecutor.execute(() -> {
                try {
                    future.complete(executePrompt(buildPromptRequest(imageBytes, imageFormat, options)));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                } finally {
                    wipeBytes(imageBytes);
                }
            });
            return future;
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
            throw e;
        }
    }

    private Prompt buildPromptRequest(byte[] imageBytes, String imageFormat, VdpOptions options) {
        MimeType mimeType = resolveMimeType(imageFormat);
        UserMessage userMessage = UserMessage.builder()
                .text(buildPrompt(options))
                .media(Media.builder()
                        .mimeType(mimeType)
                        .data(imageBytes)
                        .name("session-upload-image")
                        .build())
                .build();
        return Prompt.builder()
                .chatOptions(ZhiPuAiChatOptions.builder()
                        .model(resolveModelId())
                        .maxTokens(properties.getMaxTokens())
                        .temperature(properties.getTemperature())
                        .build())
                .messages(List.of(userMessage))
                .build();
    }

    private VdpPageResult mapResponse(String response) {
        try {
            JsonNode root = readResponseJson(stripFence(response));
            String markdown = textValue(root, "markdown");
            String interpretiveNote = textValue(root, "interpretiveNote");
            String visualType = normalizeVisualType(textValue(root, "visualType"));

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("contentOrigin", "VDP_TRANSCRIBED");
            metadata.put("visualType", visualType);
            metadata.put("degraded", false);
            metadata.put("engineId", "vlm");
            metadata.put("promptVersion", properties.getPromptVersion());
            metadata.put("modelId", resolveModelId());
            if (StringUtils.hasText(interpretiveNote)) {
                metadata.put("interpretiveNote", interpretiveNote.trim());
            }

            return new VdpPageResult(0, markdown, VdpPageStatus.SUCCESS, metadata);
        } catch (Exception e) {
            String cleaned = stripFence(response);
            String recoveredMarkdown = recoverMarkdownFromNonJsonResponse(cleaned);
            if (StringUtils.hasText(recoveredMarkdown)) {
                log.warn("VDP response was not valid JSON, recovered markdown fallback: error={}", e.getMessage());
                return new VdpPageResult(
                        0,
                        recoveredMarkdown,
                        VdpPageStatus.DEGRADED,
                        Map.of(
                                "contentOrigin", "VDP_TRANSCRIBED",
                                "visualType", inferVisualTypeFromRecoveredMarkdown(recoveredMarkdown),
                                "degraded", true,
                                "engineId", engineId(),
                                "promptVersion", promptVersion(),
                                "modelId", resolveModelId(),
                                "interpretiveNote", "Recovered markdown from non-JSON VDP response"
                        )
                );
            }
            log.warn("VDP response was not valid JSON, skip indexing raw response: error={}", e.getMessage());
            return degradedResult("Non-JSON VDP response: " + abbreviateForMetadata(cleaned));
        }
    }

    private JsonNode readResponseJson(String response) throws JsonProcessingException {
        try {
            return objectMapper.readTree(response);
        } catch (JsonProcessingException strictFailure) {
            try {
                return objectMapper.copy()
                        .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())
                        .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature())
                        .readTree(response);
            } catch (JsonProcessingException ignored) {
                throw strictFailure;
            }
        }
    }

    private VdpPageResult degradedResult(String reason) {
        String placeholder = StringUtils.hasText(properties.getFailurePlaceholder())
                ? properties.getFailurePlaceholder().trim()
                : promptLoader.load(PromptConstants.FALLBACK_VLM_FAILURE);
        return new VdpPageResult(
                0,
                "",
                VdpPageStatus.DEGRADED,
                Map.of(
                        "contentOrigin", "VDP_TRANSCRIBED",
                        "visualType", "IMAGE",
                        "degraded", true,
                        "engineId", "vlm",
                        "promptVersion", properties.getPromptVersion(),
                        "modelId", resolveModelId(),
                        "interpretiveNote", buildInterpretiveNote(placeholder, reason)
                )
        );
    }

    private String buildInterpretiveNote(String placeholder, String reason) {
        if (!StringUtils.hasText(reason)) {
            return placeholder;
        }
        return placeholder + ": " + reason.trim();
    }

    private CacheLookupContext buildCacheLookupContext(byte[] imageBytes, VdpOptions options) {
        PipelineSource pipelineSource = resolvePipelineSource(options);
        return new CacheLookupContext(
                pipelineSource,
                sha256Hex(imageBytes),
                resolveSessionId(options)
        );
    }

    private VdpPageResult cacheAndReturn(CacheLookupContext cacheLookupContext, VdpPageResult result) {
        vdpResultCacheService.put(
                cacheLookupContext.pipelineSource(),
                engineId(),
                promptVersion(),
                cacheLookupContext.contentDigest(),
                cacheLookupContext.sessionId(),
                result
        );
        return result;
    }

    private String abbreviateForMetadata(String value) {
        if (!StringUtils.hasText(value)) {
            return "blank response";
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }

    private String stripFence(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private String textValue(JsonNode root, String fieldName) {
        JsonNode node = root == null ? null : root.get(fieldName);
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private String normalizeVisualType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "IMAGE";
        }
        String normalized = raw.trim().toUpperCase();
        return switch (normalized) {
            case "TABLE", "CHART", "FORMULA", "IMAGE" -> normalized;
            default -> "IMAGE";
        };
    }

    private MimeType resolveMimeType(String imageFormat) {
        String normalized = imageFormat == null ? "" : imageFormat.trim().toLowerCase();
        return switch (normalized) {
            case "jpg", "jpeg" -> Media.Format.IMAGE_JPEG;
            case "gif" -> Media.Format.IMAGE_GIF;
            case "webp" -> Media.Format.IMAGE_WEBP;
            case "png", "" -> Media.Format.IMAGE_PNG;
            default -> {
                try {
                    yield MimeTypeUtils.parseMimeType(normalized.startsWith("image/") ? normalized : "image/" + normalized);
                } catch (Exception ignored) {
                    yield Media.Format.IMAGE_PNG;
                }
            }
        };
    }

    private String resolveModelId() {
        return StringUtils.hasText(properties.getModelId()) ? properties.getModelId().trim() : properties.getClientId();
    }

    private void wipeBytes(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private PipelineSource resolvePipelineSource(VdpOptions options) {
        Object value = options == null ? null : options.extra().get("pipelineSource");
        return value instanceof PipelineSource source ? source : PipelineSource.KNOWLEDGE;
    }

    private String resolveSessionId(VdpOptions options) {
        Object value = options == null ? null : options.extra().get("sessionId");
        return value == null ? null : value.toString();
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes == null ? new byte[0] : bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute VDP image digest", e);
        }
    }

    private String recoverMarkdownFromNonJsonResponse(String cleanedResponse) {
        if (!StringUtils.hasText(cleanedResponse)) {
            return "";
        }
        String tableMarkdown = extractMarkdownTable(cleanedResponse);
        if (StringUtils.hasText(tableMarkdown)) {
            return tableMarkdown;
        }
        String stripped = stripConversationalPreamble(cleanedResponse);
        if (!StringUtils.hasText(stripped) || looksConversational(stripped)) {
            return "";
        }
        if (looksLikeMarkdownStructure(stripped) || stripped.length() >= 120) {
            return stripped;
        }
        return "";
    }

    private String extractMarkdownTable(String cleanedResponse) {
        String[] lines = cleanedResponse.split("\\R");
        for (int i = 1; i < lines.length; i++) {
            if (!isMarkdownTableSeparator(lines[i])) {
                continue;
            }
            String header = lines[i - 1].trim();
            if (!header.contains("|")) {
                continue;
            }
            StringBuilder table = new StringBuilder();
            table.append(header).append('\n').append(lines[i].trim());
            for (int j = i + 1; j < lines.length; j++) {
                String line = lines[j].trim();
                if (!StringUtils.hasText(line)) {
                    break;
                }
                if (!line.contains("|")) {
                    break;
                }
                table.append('\n').append(line);
            }
            return table.toString().trim();
        }
        return "";
    }

    private boolean isMarkdownTableSeparator(String line) {
        if (!StringUtils.hasText(line)) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.matches("^\\|?\\s*:?-{3,}:?(\\s*\\|\\s*:?-{3,}:?)+\\s*\\|?$");
    }

    private String stripConversationalPreamble(String cleanedResponse) {
        String normalized = cleanedResponse.trim();
        String[] lines = normalized.split("\\R");
        if (lines.length <= 1) {
            return normalized;
        }
        String firstLine = lines[0].trim().toLowerCase();
        if (!firstLine.startsWith("here is")
                && !firstLine.startsWith("here's")
                && !firstLine.startsWith("analysis")
                && !firstLine.startsWith("the image")
                && !firstLine.startsWith("this image")
                && !firstLine.startsWith("this appears")
                && !firstLine.startsWith("i can see")
                && !firstLine.startsWith("i see")
                && !firstLine.startsWith("below is")) {
            return normalized;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            if (!StringUtils.hasText(lines[i]) && builder.length() == 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(lines[i].trim());
        }
        return builder.toString().trim();
    }

    private boolean looksLikeMarkdownStructure(String content) {
        String trimmed = content.trim();
        return trimmed.startsWith("#")
                || trimmed.startsWith("- ")
                || trimmed.startsWith("* ")
                || trimmed.startsWith("> ")
                || trimmed.contains("\n- ")
                || trimmed.contains("\n* ")
                || trimmed.contains("|---|")
                || trimmed.contains("\n## ");
    }

    private boolean looksConversational(String content) {
        String normalized = content.trim().toLowerCase();
        return normalized.startsWith("here is")
                || normalized.startsWith("here's")
                || normalized.startsWith("this image")
                || normalized.startsWith("i can see")
                || normalized.startsWith("it appears");
    }

    private String inferVisualTypeFromRecoveredMarkdown(String markdown) {
        return markdown != null && markdown.contains("|---") ? "TABLE" : "IMAGE";
    }

    private record CacheLookupContext(PipelineSource pipelineSource, String contentDigest, String sessionId) {
    }
}
