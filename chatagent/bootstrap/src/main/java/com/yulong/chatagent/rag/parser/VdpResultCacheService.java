package com.yulong.chatagent.rag.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Session-local and knowledge-global cache for deduplicating visual parsing results.
 */
@Component
@Slf4j
public class VdpResultCacheService {

    private static final String KNOWLEDGE_CACHE_KEY_PREFIX = "vdp:knowledge:";

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final VdpCacheProperties properties;
    private final SessionScopedVdpCacheStore sessionCacheStore;

    public VdpResultCacheService(ObjectMapper objectMapper,
                                 ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
                                 VdpCacheProperties properties) {
        this.objectMapper = objectMapper;
        this.stringRedisTemplate = stringRedisTemplateProvider.getIfAvailable();
        this.properties = properties;
        this.sessionCacheStore = new SessionScopedVdpCacheStore(
                properties.getSessionMaxSize(),
                properties.getSessionTtlMinutes()
        );
    }

    public VdpPageResult get(PipelineSource pipelineSource,
                             String engineId,
                             String promptVersion,
                             String contentDigest,
                             String sessionId) {
        if (!properties.isEnabled() || !StringUtils.hasText(contentDigest)) {
            return null;
        }
        if (pipelineSource == PipelineSource.SESSION) {
            return sessionCacheStore.get(sessionId, keySuffix(engineId, promptVersion, contentDigest));
        }
        String cacheKey = knowledgeCacheKey(engineId, promptVersion, contentDigest);
        if (stringRedisTemplate == null) {
            return null;
        }
        String cached = null;
        try {
            cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (!StringUtils.hasText(cached)) {
                return null;
            }
            return objectMapper.readValue(cached, VdpPageResult.class);
        } catch (Exception e) {
            log.warn("Failed to load VDP knowledge cache entry: key={}, preview={}, error={}",
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
        if (!shouldCache(pipelineSource, contentDigest, result)) {
            return;
        }
        if (pipelineSource == PipelineSource.SESSION) {
            sessionCacheStore.put(sessionId, keySuffix(engineId, promptVersion, contentDigest), result);
            return;
        }
        if (stringRedisTemplate == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    knowledgeCacheKey(engineId, promptVersion, contentDigest),
                    objectMapper.writeValueAsString(result),
                    Duration.ofMinutes(Math.max(1L, properties.getKnowledgeTtlMinutes()))
            );
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize VDP cache entry: engineId={}, digest={}, error={}",
                    engineId, contentDigest, e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to store VDP knowledge cache entry: engineId={}, digest={}, error={}",
                    engineId, contentDigest, e.getMessage());
        }
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
}
