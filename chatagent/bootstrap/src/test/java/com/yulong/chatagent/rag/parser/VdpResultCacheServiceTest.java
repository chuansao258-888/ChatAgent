package com.yulong.chatagent.rag.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VdpResultCacheServiceTest {

    @Test
    void shouldRecordImageCacheHitAndMissMetrics() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        VdpResultCacheService cacheService = new VdpResultCacheService(
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
                new VdpPageResult(0, "| cached |", VdpPageStatus.SUCCESS, Map.of("visualType", "TABLE"))
        );
        assertThat(cacheService.get(PipelineSource.SESSION, "vlm", "v1", "digest-1", "session-1")).isNotNull();

        assertThat(meterRegistry.get("vdp.cache.miss").tags("layer", "image").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("vdp.cache.hit").tags("layer", "image").counter().count()).isEqualTo(1.0d);
    }
}
