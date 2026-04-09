package com.yulong.chatagent.rag.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VdpPageCacheServiceTest {

    @Test
    void shouldPipelineKnowledgeBatchPageCacheWrites() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        RedisConnection connection = mock(RedisConnection.class);
        RedisStringCommands stringCommands = mock(RedisStringCommands.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.getStringSerializer()).thenReturn(new StringRedisSerializer());
        when(connection.stringCommands()).thenReturn(stringCommands);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RedisCallback<Object> callback = invocation.getArgument(0, RedisCallback.class);
            callback.doInRedis(connection);
            return List.of();
        }).when(stringRedisTemplate).executePipelined(any(RedisCallback.class));

        VdpPageCacheService cacheService = new VdpPageCacheService(new ObjectMapper(), provider, new VdpCacheProperties());

        cacheService.putAll(
                PipelineSource.KNOWLEDGE,
                "mineru",
                "v1",
                Map.of(
                        "digest-1", new VdpPageResult(0, "| A | 1 |", VdpPageStatus.SUCCESS, Map.of()),
                        "digest-2", new VdpPageResult(1, "| B | 2 |", VdpPageStatus.SUCCESS, Map.of()),
                        "digest-3", new VdpPageResult(2, "", VdpPageStatus.FAILED, Map.of())
                ),
                null
        );

        verify(stringRedisTemplate, times(1)).executePipelined(any(RedisCallback.class));
        verify(stringCommands, times(2)).setEx(any(byte[].class), anyLong(), any(byte[].class));
    }

    @Test
    void shouldReadBackSessionScopedPageCacheEntries() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        VdpPageCacheService cacheService = new VdpPageCacheService(new ObjectMapper(), provider, new VdpCacheProperties());
        VdpPageResult cachedResult = new VdpPageResult(0, "| cached |", VdpPageStatus.SUCCESS, Map.of("visualType", "TABLE"));

        cacheService.put(PipelineSource.SESSION, "vlm", "v1", "digest-1", "session-1", cachedResult);

        assertThat(cacheService.get(PipelineSource.SESSION, "vlm", "v1", "digest-1", "session-1"))
                .isEqualTo(cachedResult);
    }

    @Test
    void shouldDeleteCorruptedKnowledgeCacheEntriesOnRead() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doReturn("not-json").when(valueOperations).get(eq("vdp:pdf-page:knowledge:mineru:v1:digest-1"));

        VdpPageCacheService cacheService = new VdpPageCacheService(new ObjectMapper(), provider, new VdpCacheProperties());

        assertThat(cacheService.get(PipelineSource.KNOWLEDGE, "mineru", "v1", "digest-1", null)).isNull();
        verify(stringRedisTemplate).delete("vdp:pdf-page:knowledge:mineru:v1:digest-1");
    }

    @Test
    void shouldRecordPageCacheHitAndMissMetrics() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        VdpPageCacheService cacheService = new VdpPageCacheService(
                new ObjectMapper(),
                provider,
                new VdpCacheProperties(),
                meterRegistry
        );

        assertThat(cacheService.get(PipelineSource.SESSION, "vlm", "v1", "missing", "session-1")).isNull();
        cacheService.put(
                PipelineSource.SESSION,
                "vlm",
                "v1",
                "digest-1",
                "session-1",
                new VdpPageResult(0, "| cached |", VdpPageStatus.SUCCESS, Map.of())
        );
        assertThat(cacheService.get(PipelineSource.SESSION, "vlm", "v1", "digest-1", "session-1")).isNotNull();

        assertThat(meterRegistry.get("vdp.cache.miss").tags("layer", "page").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("vdp.cache.hit").tags("layer", "page").counter().count()).isEqualTo(1.0d);
    }
}
