package com.yulong.chatagent.rag.retrieve;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.knowledge.port.KnowledgeDocumentEnhancementRepository;
import com.yulong.chatagent.rag.ingestion.DocumentEnhancementResult;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import com.yulong.chatagent.support.dto.KnowledgeDocumentEnhancementDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Manages document-level rerank signals across PostgreSQL and Redis with fail-open cache behavior.
 */
@Component
@Slf4j
public class KnowledgeDocumentSignalService {

    private static final String CACHE_KEY_PREFIX = "doc_signal:";

    private final KnowledgeDocumentEnhancementRepository repository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration cacheTtl;

    public KnowledgeDocumentSignalService(KnowledgeDocumentEnhancementRepository repository,
                                          StringRedisTemplate stringRedisTemplate,
                                          ObjectMapper objectMapper,
                                          KnowledgeDocumentSignalProperties properties) {
        this.repository = repository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.cacheTtl = Duration.ofMinutes(Math.max(properties.getCacheTtlMinutes(), 1L));
    }

    public void saveOrUpdate(String documentId, DocumentEnhancementResult enhancement) {
        if (!StringUtils.hasText(documentId)) {
            return;
        }
        KnowledgeDocumentSignal signal = KnowledgeDocumentSignal.fromEnhancement(documentId, enhancement);
        LocalDateTime now = LocalDateTime.now();
        boolean saved = repository.saveOrUpdate(KnowledgeDocumentEnhancementDTO.builder()
                .knowledgeDocumentId(documentId)
                .enhancerCacheKey(signal.enhancerCacheKey())
                .keywords(signal.keywords())
                .questions(signal.questions())
                .metadata(signal.metadata())
                .createdAt(now)
                .updatedAt(now)
                .build());
        if (!saved) {
            throw new IllegalStateException("Failed to save knowledge document enhancement");
        }
        cacheSignalQuietly(signal);
    }

    public void delete(String documentId) {
        if (!StringUtils.hasText(documentId)) {
            return;
        }
        repository.deleteByKnowledgeDocumentId(documentId);
        evictCache(documentId);
    }

    public void evictCache(String documentId) {
        if (!StringUtils.hasText(documentId)) {
            return;
        }
        try {
            stringRedisTemplate.delete(cacheKey(documentId));
        } catch (Exception e) {
            log.warn("Failed to evict knowledge document signal cache: documentId={}, error={}", documentId, e.getMessage());
        }
    }

    public void evictCaches(List<String> documentIds) {
        List<String> keys = distinctCacheKeys(documentIds);
        if (keys.isEmpty()) {
            return;
        }
        try {
            stringRedisTemplate.delete(keys);
        } catch (Exception e) {
            log.warn("Failed to batch-evict knowledge document signal cache: documentCount={}, error={}",
                    keys.size(), e.getMessage());
        }
    }

    public List<MilvusSearchHit> attachSignals(List<MilvusSearchHit> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, KnowledgeDocumentSignal> signals = loadByDocumentIds(candidates.stream()
                .map(MilvusSearchHit::documentId)
                .toList());
        if (signals.isEmpty()) {
            return candidates;
        }
        return candidates.stream()
                .map(hit -> attachSignal(hit, signals.get(hit.documentId())))
                .toList();
    }

    Map<String, KnowledgeDocumentSignal> loadByDocumentIds(List<String> documentIds) {
        LinkedHashSet<String> distinctIds = new LinkedHashSet<>();
        if (documentIds != null) {
            for (String documentId : documentIds) {
                if (StringUtils.hasText(documentId)) {
                    distinctIds.add(documentId);
                }
            }
        }
        if (distinctIds.isEmpty()) {
            return Map.of();
        }

        List<String> orderedIds = new ArrayList<>(distinctIds);
        Map<String, KnowledgeDocumentSignal> results = new LinkedHashMap<>();
        LinkedHashSet<String> misses = new LinkedHashSet<>(orderedIds);

        try {
            List<String> cachedValues = stringRedisTemplate.opsForValue()
                    .multiGet(orderedIds.stream().map(this::cacheKey).toList());
            if (cachedValues != null) {
                for (int i = 0; i < Math.min(orderedIds.size(), cachedValues.size()); i++) {
                    String cached = cachedValues.get(i);
                    if (!StringUtils.hasText(cached)) {
                        continue;
                    }
                    String documentId = orderedIds.get(i);
                    try {
                        results.put(documentId, objectMapper.readValue(cached, KnowledgeDocumentSignal.class));
                        misses.remove(documentId);
                    } catch (Exception e) {
                        log.warn("Failed to deserialize cached knowledge document signal: documentId={}, error={}",
                                documentId, e.getMessage());
                        evictCache(documentId);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Knowledge document signal Redis lookup skipped: documentCount={}, error={}",
                    orderedIds.size(), e.getMessage());
        }

        if (!misses.isEmpty()) {
            List<KnowledgeDocumentEnhancementDTO> persisted = repository.findByKnowledgeDocumentIds(new ArrayList<>(misses));
            LinkedHashSet<String> foundIds = new LinkedHashSet<>();
            for (KnowledgeDocumentEnhancementDTO dto : persisted) {
                KnowledgeDocumentSignal signal = new KnowledgeDocumentSignal(
                        dto.getKnowledgeDocumentId(),
                        dto.getEnhancerCacheKey(),
                        dto.getKeywords(),
                        dto.getQuestions(),
                        dto.getMetadata()
                );
                results.put(dto.getKnowledgeDocumentId(), signal);
                foundIds.add(dto.getKnowledgeDocumentId());
                cacheSignalQuietly(signal);
            }
            for (String missingDocumentId : misses) {
                if (foundIds.contains(missingDocumentId)) {
                    continue;
                }
                KnowledgeDocumentSignal emptySignal = KnowledgeDocumentSignal.empty(missingDocumentId);
                results.put(missingDocumentId, emptySignal);
                cacheSignalQuietly(emptySignal);
            }
        }

        return results;
    }

    private MilvusSearchHit attachSignal(MilvusSearchHit hit, KnowledgeDocumentSignal signal) {
        if (hit == null || signal == null) {
            return hit;
        }
        return hit.withDocumentKeywords(signal.keywords())
                .withDocumentQuestions(signal.questions());
    }

    private void cacheSignalQuietly(KnowledgeDocumentSignal signal) {
        if (signal == null || !StringUtils.hasText(signal.documentId())) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    cacheKey(signal.documentId()),
                    objectMapper.writeValueAsString(signal),
                    cacheTtl
            );
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize knowledge document signal for Redis: documentId={}, error={}",
                    signal.documentId(), e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to cache knowledge document signal in Redis: documentId={}, error={}",
                    signal.documentId(), e.getMessage());
        }
    }

    private String cacheKey(String documentId) {
        return CACHE_KEY_PREFIX + documentId;
    }

    private List<String> distinctCacheKeys(Collection<String> documentIds) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (documentIds == null) {
            return List.of();
        }
        for (String documentId : documentIds) {
            if (StringUtils.hasText(documentId)) {
                keys.add(cacheKey(documentId));
            }
        }
        return new ArrayList<>(keys);
    }
}
