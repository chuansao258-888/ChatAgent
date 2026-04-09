package com.yulong.chatagent.rag.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PDF page-result cache used before rendering so repeated document parses can skip expensive page rasterization.
 */
@Component
@Slf4j
public class VdpPageCacheService {

    private static final String KNOWLEDGE_CACHE_KEY_PREFIX = "vdp:pdf-page:knowledge:";

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final VdpCacheProperties properties;
    private final SessionScopedVdpCacheStore sessionCacheStore;
    private final MeterRegistry meterRegistry;

    @Autowired
    public VdpPageCacheService(ObjectMapper objectMapper,
                               ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
                               VdpCacheProperties properties,
                               ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(objectMapper, stringRedisTemplateProvider, properties, meterRegistryProvider.getIfAvailable());
    }

    VdpPageCacheService(ObjectMapper objectMapper,
                        ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
                        VdpCacheProperties properties) {
        this(objectMapper, stringRedisTemplateProvider, properties, (MeterRegistry) null);
    }

    VdpPageCacheService(ObjectMapper objectMapper,
                        ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
                        VdpCacheProperties properties,
                        MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.stringRedisTemplate = stringRedisTemplateProvider.getIfAvailable();
        this.properties = properties;
        this.sessionCacheStore = new SessionScopedVdpCacheStore(
                properties.getSessionMaxSize(),
                properties.getSessionTtlMinutes()
        );
        this.meterRegistry = meterRegistry;
    }

    public VdpPageResult get(PipelineSource pipelineSource,
                             String engineId,
                             String promptVersion,
                             String contentDigest,
                             String sessionId) {
        if (!properties.isEnabled() || !StringUtils.hasText(contentDigest)) {
            return null;
        }
        String keySuffix = keySuffix(engineId, promptVersion, contentDigest);
        if (pipelineSource == PipelineSource.SESSION) {
            VdpPageResult cached = sessionCacheStore.get(sessionId, keySuffix);
            recordCacheLookup("page", cached != null);
            return cached;
        }
        if (stringRedisTemplate == null) {
            recordCacheLookup("page", false);
            return null;
        }
        String cacheKey = knowledgeCacheKey(engineId, promptVersion, contentDigest);
        String cached = null;
        try {
            cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (!StringUtils.hasText(cached)) {
                recordCacheLookup("page", false);
                return null;
            }
            VdpPageResult result = objectMapper.readValue(cached, VdpPageResult.class);
            recordCacheLookup("page", true);
            return result;
        } catch (Exception e) {
            recordCacheLookup("page", false);
            log.warn("Failed to load VDP PDF-page cache entry: key={}, preview={}, error={}",
                    cacheKey, abbreviatePreview(cached), e.getMessage());
            try {
                stringRedisTemplate.delete(cacheKey);
            } catch (Exception ignored) {
                // fail-open
            }
            return null;
        }
    }

    public void put(PipelineSource pipelineSource,
                    String engineId,
                    String promptVersion,
                    String contentDigest,
                    String sessionId,
                    VdpPageResult result) {
        putAll(
                pipelineSource,
                engineId,
                promptVersion,
                Map.of(contentDigest, result),
                sessionId
        );
    }

    public void putAll(PipelineSource pipelineSource,
                       String engineId,
                       String promptVersion,
                       Map<String, VdpPageResult> pageResultsByDigest,
                       String sessionId) {
        if (pageResultsByDigest == null || pageResultsByDigest.isEmpty()) {
            return;
        }
        Map<String, VdpPageResult> cacheableEntries = new LinkedHashMap<>();
        pageResultsByDigest.forEach((contentDigest, result) -> {
            if (shouldCache(pipelineSource, contentDigest, result)) {
                cacheableEntries.put(contentDigest.trim(), result);
            }
        });
        if (cacheableEntries.isEmpty()) {
            return;
        }
        if (pipelineSource == PipelineSource.SESSION) {
            cacheableEntries.forEach((contentDigest, result) ->
                    sessionCacheStore.put(sessionId, keySuffix(engineId, promptVersion, contentDigest), result)
            );
            return;
        }
        if (stringRedisTemplate == null) {
            return;
        }
        Map<String, String> payloads = serializeKnowledgePayloads(engineId, promptVersion, cacheableEntries);
        if (payloads.isEmpty()) {
            return;
        }
        long ttlSeconds = Math.max(60L, Duration.ofMinutes(Math.max(1L, properties.getKnowledgeTtlMinutes())).getSeconds());
        try {
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                writeKnowledgeEntries(connection, payloads, ttlSeconds);
                return null;
            });
        } catch (Exception e) {
            log.warn("Failed to store VDP PDF-page cache entries in pipeline: engineId={}, count={}, error={}",
                    engineId, payloads.size(), e.getMessage());
        }
    }

    private Map<String, String> serializeKnowledgePayloads(String engineId,
                                                           String promptVersion,
                                                           Map<String, VdpPageResult> cacheableEntries) {
        Map<String, String> payloads = new LinkedHashMap<>();
        cacheableEntries.forEach((contentDigest, result) -> {
            try {
                payloads.put(
                        knowledgeCacheKey(engineId, promptVersion, contentDigest),
                        objectMapper.writeValueAsString(result)
                );
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize VDP PDF-page cache entry: engineId={}, digest={}, error={}",
                        engineId, contentDigest, e.getMessage());
            }
        });
        return payloads;
    }

    private void writeKnowledgeEntries(RedisConnection connection,
                                       Map<String, String> payloads,
                                       long ttlSeconds) {
        if (connection == null || payloads.isEmpty()) {
            return;
        }
        payloads.forEach((cacheKey, payload) -> {
            byte[] keyBytes = stringRedisTemplate.getStringSerializer().serialize(cacheKey);
            byte[] valueBytes = stringRedisTemplate.getStringSerializer().serialize(payload);
            if (keyBytes != null && valueBytes != null) {
                connection.stringCommands().setEx(keyBytes, ttlSeconds, valueBytes);
            }
        });
    }

    private boolean shouldCache(PipelineSource pipelineSource, String contentDigest, VdpPageResult result) {
        if (!properties.isEnabled() || !StringUtils.hasText(contentDigest) || result == null) {
            return false;
        }
        if (pipelineSource == PipelineSource.SESSION) {
            return result.status() != VdpPageStatus.FAILED;
        }
        return result.status() == VdpPageStatus.SUCCESS && StringUtils.hasText(result.markdown());
    }

    private String knowledgeCacheKey(String engineId, String promptVersion, String contentDigest) {
        return KNOWLEDGE_CACHE_KEY_PREFIX + keySuffix(engineId, promptVersion, contentDigest);
    }

    private String keySuffix(String engineId, String promptVersion, String contentDigest) {
        String normalizedEngineId = StringUtils.hasText(engineId) ? engineId.trim() : "unknown";
        String normalizedPromptVersion = StringUtils.hasText(promptVersion) ? promptVersion.trim() : "default";
        return normalizedEngineId + ":" + normalizedPromptVersion + ":" + contentDigest.trim();
    }

    private String abbreviatePreview(String cached) {
        if (!StringUtils.hasText(cached)) {
            return "<empty>";
        }
        String normalized = cached.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }

    private void recordCacheLookup(String layer, boolean hit) {
        VdpMetricsSupport.increment(
                meterRegistry,
                hit ? "vdp.cache.hit" : "vdp.cache.miss",
                "layer",
                VdpMetricsSupport.tagValue(layer, "unknown")
        );
    }
}
