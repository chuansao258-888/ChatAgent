package com.yulong.chatagent.intent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.port.AgentRepository;
import com.yulong.chatagent.intent.model.IntentNodeStatus;
import com.yulong.chatagent.intent.port.IntentKnowledgeBaseRepository;
import com.yulong.chatagent.intent.port.IntentNodeRepository;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.IntentKnowledgeBaseDTO;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis-backed cache for active intent tree snapshots.
 */
@Component
@Slf4j
public class DefaultIntentTreeCacheManager implements IntentTreeCacheManager {

    private static final String CACHE_KEY_PREFIX = "chatagent:intent:tree:";

    private final AgentRepository agentRepository;
    private final IntentNodeRepository intentNodeRepository;
    private final IntentKnowledgeBaseRepository intentKnowledgeBaseRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration cacheTtl;

    public DefaultIntentTreeCacheManager(AgentRepository agentRepository,
                                         IntentNodeRepository intentNodeRepository,
                                         IntentKnowledgeBaseRepository intentKnowledgeBaseRepository,
                                         StringRedisTemplate stringRedisTemplate,
                                         ObjectMapper objectMapper,
                                         @Value("${chatagent.intent.cache-ttl-minutes:30}") long cacheTtlMinutes) {
        this.agentRepository = agentRepository;
        this.intentNodeRepository = intentNodeRepository;
        this.intentKnowledgeBaseRepository = intentKnowledgeBaseRepository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.cacheTtl = Duration.ofMinutes(Math.max(cacheTtlMinutes, 1L));
    }

    @Override
    public IntentTreeSnapshot loadActiveSnapshot(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return IntentTreeSnapshot.empty(agentId);
        }

        AgentDTO agent = agentRepository.findById(agentId);
        Integer activeVersion = resolveActiveVersion(agent);
        if (activeVersion == null) {
            return IntentTreeSnapshot.empty(agentId);
        }

        String cacheKey = cacheKey(agentId);
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(cached)) {
            try {
                IntentTreeSnapshot snapshot = objectMapper.readValue(cached, IntentTreeSnapshot.class);
                if (activeVersion.equals(snapshot.getVersion())) {
                    return snapshot;
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize cached intent tree snapshot: agentId={}, version={}, error={}",
                        agentId,
                        activeVersion,
                        e.getMessage());
            }
        }

        IntentTreeSnapshot snapshot = loadFromPersistence(agentId, activeVersion);
        try {
            stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(snapshot), cacheTtl);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize intent tree snapshot for Redis: agentId={}, version={}, error={}",
                    agentId,
                    activeVersion,
                    e.getMessage());
        }
        return snapshot;
    }

    @Override
    public void evictActiveSnapshot(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return;
        }
        stringRedisTemplate.delete(cacheKey(agentId));
    }

    @Override
    public IntentTreeSnapshot refreshActiveSnapshot(String agentId) {
        evictActiveSnapshot(agentId);
        return loadActiveSnapshot(agentId);
    }

    private IntentTreeSnapshot loadFromPersistence(String agentId, int version) {
        List<IntentNodeDTO> allNodes = intentNodeRepository.findByAgentIdAndVersion(agentId, version);
        List<IntentNodeDTO> activeNodes = new ArrayList<>();
        for (IntentNodeDTO node : allNodes) {
            if (node == null) {
                continue;
            }
            if (node.getStatus() != IntentNodeStatus.PUBLISHED) {
                continue;
            }
            if (Boolean.FALSE.equals(node.getEnabled())) {
                continue;
            }
            activeNodes.add(node);
        }

        List<String> nodeIds = activeNodes.stream()
                .map(IntentNodeDTO::getId)
                .toList();
        Map<String, List<String>> kbIdsByNodeId = new LinkedHashMap<>();
        for (IntentKnowledgeBaseDTO binding : intentKnowledgeBaseRepository.findByIntentNodeIds(nodeIds)) {
            kbIdsByNodeId.computeIfAbsent(binding.getIntentNodeId(), ignored -> new ArrayList<>())
                    .add(binding.getKnowledgeBaseId());
        }
        return new IntentTreeSnapshot(agentId, version, activeNodes, kbIdsByNodeId);
    }

    private Integer resolveActiveVersion(AgentDTO agent) {
        if (agent == null || agent.getActiveIntentVersion() == null || agent.getActiveIntentVersion() <= 0) {
            return null;
        }
        return agent.getActiveIntentVersion();
    }

    private String cacheKey(String agentId) {
        return CACHE_KEY_PREFIX + agentId + ":active";
    }
}
