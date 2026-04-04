package com.yulong.chatagent.rag.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.chat.ChatModelRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class VlmVdpEngineTest {

    private final ChatModelRouter chatModelRouter = mock(ChatModelRouter.class);
    private final ChatClient chatClient = mock(ChatClient.class);
    private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
    private VlmVdpProperties properties;
    private Executor executor;

    @BeforeEach
    void setUp() {
        properties = new VlmVdpProperties();
        properties.setClientId("glm-4.6");
        properties.setModelId("glm-4v-flash");
        properties.setPromptVersion("v1");
        properties.setTimeoutMs(1000L);
        properties.setFailurePlaceholder("[图像解析失败]");

        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(1);
        taskExecutor.setMaxPoolSize(1);
        taskExecutor.setQueueCapacity(0);
        taskExecutor.initialize();
        executor = taskExecutor;
    }

    @Test
    void shouldDegradeWhenModelInvocationFails() {
        when(chatModelRouter.route("glm-4.6")).thenReturn(chatClient);
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenThrow(new RuntimeException("LLM unavailable"));

        VlmVdpEngine engine = new VlmVdpEngine(chatModelRouter, new ObjectMapper(), properties, cacheServiceWithoutRedis(), executor);

        VdpPageResult result = engine.parsePage(imageSupplier(), "png", new VdpOptions(false, "zh", null));

        assertThat(result.status()).isEqualTo(VdpPageStatus.DEGRADED);
        assertThat(result.markdown()).isEmpty();
        assertThat(result.metadata()).containsEntry("degraded", true);
        assertThat(result.metadata().get("interpretiveNote").toString()).contains("LLM unavailable");
    }

    @Test
    void shouldParseJsonResponseFromModel() {
        when(chatModelRouter.route("glm-4.6")).thenReturn(chatClient);
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("""
                {
                  "markdown":"| A | B |\\n|---|---|\\n| 1 | 2 |",
                  "interpretiveNote":"Simple table",
                  "visualType":"TABLE"
                }
                """);

        VlmVdpEngine engine = new VlmVdpEngine(chatModelRouter, new ObjectMapper(), properties, cacheServiceWithoutRedis(), executor);

        VdpPageResult result = engine.parsePage(imageSupplier(), "png", new VdpOptions(false, "zh", null));

        assertThat(result.status()).isEqualTo(VdpPageStatus.SUCCESS);
        assertThat(result.markdown()).contains("| A | B |");
        assertThat(result.metadata()).containsEntry("visualType", "TABLE");
        assertThat(result.metadata()).containsEntry("interpretiveNote", "Simple table");
    }

    @Test
    void shouldNotIndexRawAssistantTextWhenResponseIsNotJson() {
        when(chatModelRouter.route("glm-4.6")).thenReturn(chatClient);
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("Here is the analysis of the image: this appears to be a table.");

        VlmVdpEngine engine = new VlmVdpEngine(chatModelRouter, new ObjectMapper(), properties, cacheServiceWithoutRedis(), executor);

        VdpPageResult result = engine.parsePage(imageSupplier(), "png", new VdpOptions(false, "zh", null));

        assertThat(result.status()).isEqualTo(VdpPageStatus.DEGRADED);
        assertThat(result.markdown()).isEmpty();
        assertThat(result.metadata().get("interpretiveNote").toString()).contains("Non-JSON VDP response");
    }

    @Test
    void shouldRecoverMarkdownTableWhenModelReturnsNonJsonMarkdown() {
        when(chatModelRouter.route("glm-4.6")).thenReturn(chatClient);
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("""
                Here is the extracted table:

                | Item | Value |
                |---|---|
                | A | 1 |
                """);

        VlmVdpEngine engine = new VlmVdpEngine(chatModelRouter, new ObjectMapper(), properties, cacheServiceWithoutRedis(), executor);

        VdpPageResult result = engine.parsePage(imageSupplier(), "png", new VdpOptions(false, "zh", null));

        assertThat(result.status()).isEqualTo(VdpPageStatus.DEGRADED);
        assertThat(result.markdown()).contains("| Item | Value |");
        assertThat(result.metadata()).containsEntry("visualType", "TABLE");
        assertThat(result.metadata().get("interpretiveNote").toString()).contains("Recovered markdown");
    }

    @Test
    void shouldReuseSessionCacheForRepeatedImages() {
        when(chatModelRouter.route("glm-4.6")).thenReturn(chatClient);
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("""
                {
                  "markdown":"cached markdown",
                  "interpretiveNote":"Session cache hit",
                  "visualType":"TABLE"
                }
                """);

        VlmVdpEngine engine = new VlmVdpEngine(chatModelRouter, new ObjectMapper(), properties, cacheServiceWithoutRedis(), executor);
        VdpOptions options = new VdpOptions(
                false,
                "zh",
                Map.of("pipelineSource", PipelineSource.SESSION, "sessionId", "session-1")
        );

        VdpPageResult first = engine.parsePage(imageSupplier(), "png", options);
        VdpPageResult second = engine.parsePage(imageSupplier(), "png", options);

        assertThat(second).isEqualTo(first);
        verify(requestSpec, times(1)).call();
    }

    @Test
    void shouldReuseKnowledgeRedisCacheForRepeatedImages() throws Exception {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        AtomicReference<String> stored = new AtomicReference<>();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(1, String.class));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        when(chatModelRouter.route("glm-4.6")).thenReturn(chatClient);
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("""
                {
                  "markdown":"knowledge cached markdown",
                  "interpretiveNote":"Knowledge cache hit",
                  "visualType":"TABLE"
                }
                """);

        VlmVdpEngine engine = new VlmVdpEngine(
                chatModelRouter,
                new ObjectMapper(),
                properties,
                cacheServiceWithRedis(stringRedisTemplate),
                executor
        );
        VdpOptions options = new VdpOptions(false, "zh", Map.of("pipelineSource", PipelineSource.KNOWLEDGE));

        VdpPageResult first = engine.parsePage(imageSupplier(), "png", options);
        VdpPageResult second = engine.parsePage(imageSupplier(), "png", options);

        assertThat(second).isEqualTo(first);
        verify(requestSpec, times(1)).call();
        verify(valueOperations, times(2)).get(anyString());
        verify(valueOperations, times(1)).set(anyString(), anyString(), any(Duration.class));
    }

    private Supplier<InputStream> imageSupplier() {
        return () -> new ByteArrayInputStream(new byte[]{1, 2, 3});
    }

    private VdpResultCacheService cacheServiceWithoutRedis() {
        return new VdpResultCacheService(new ObjectMapper(), objectProvider(null), new VdpCacheProperties());
    }

    private VdpResultCacheService cacheServiceWithRedis(StringRedisTemplate stringRedisTemplate) {
        return new VdpResultCacheService(new ObjectMapper(), objectProvider(stringRedisTemplate), new VdpCacheProperties());
    }

    private ObjectProvider<StringRedisTemplate> objectProvider(StringRedisTemplate stringRedisTemplate) {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(stringRedisTemplate);
        return provider;
    }
}
