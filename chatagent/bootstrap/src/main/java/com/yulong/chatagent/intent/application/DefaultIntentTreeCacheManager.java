package com.yulong.chatagent.intent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.application.InternalAssistantService;
import com.yulong.chatagent.intent.model.IntentNodeStatus;
import com.yulong.chatagent.intent.port.IntentKnowledgeBaseRepository;
import com.yulong.chatagent.intent.port.IntentNodeRepository;
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
 * Active 意图树快照缓存。
 *
 * ConversationTurnPreparationService 每轮都要拿当前线上意图树做路由；
 * 如果每次都查数据库并组装 parent/children/KB 绑定，会让聊天入口变慢。
 * 所以这里把「某个 agent 当前 active version 的 PUBLISHED 树」序列化到 Redis。
 */
@Component
@Slf4j
public class DefaultIntentTreeCacheManager implements IntentTreeCacheManager {

    /**
     * key 只按 agentId 存 active snapshot。
     *
     * version 不是 key 的一部分，而是 snapshot 内容的一部分；
     * 这样切换 active version 时只需要删除同一个 key 再重建。
     */
    private static final String CACHE_KEY_PREFIX = "chatagent:intent:tree:";

    private final InternalAssistantService internalAssistantService;
    private final IntentNodeRepository intentNodeRepository;
    private final IntentKnowledgeBaseRepository intentKnowledgeBaseRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration cacheTtl;

    public DefaultIntentTreeCacheManager(InternalAssistantService internalAssistantService,
                                         IntentNodeRepository intentNodeRepository,
                                         IntentKnowledgeBaseRepository intentKnowledgeBaseRepository,
                                         StringRedisTemplate stringRedisTemplate,
                                         ObjectMapper objectMapper,
                                         @Value("${chatagent.intent.cache-ttl-minutes:30}") long cacheTtlMinutes) {
        this.internalAssistantService = internalAssistantService;
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

        // activeVersion 来自 internal assistant，是运行时“应该使用哪个发布版本”的来源。
        Integer activeVersion = internalAssistantService.getActiveIntentVersion(agentId);
        if (activeVersion == null) {
            return IntentTreeSnapshot.empty(agentId);
        }

        String cacheKey = cacheKey(agentId);
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(cached)) {
            try {
                IntentTreeSnapshot snapshot = objectMapper.readValue(cached, IntentTreeSnapshot.class);
                // 防御性校验：如果 Redis 里还是旧版本，就忽略缓存重新加载。
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

        // 缓存未命中、缓存损坏、或版本不一致时回源数据库。
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
                // 运行时只认发布节点；draft 节点绝不参与路由。
                continue;
            }
            if (Boolean.FALSE.equals(node.getEnabled())) {
                // 已禁用节点也不进入 snapshot，相当于对线上路由隐藏。
                continue;
            }
            activeNodes.add(node);
        }

        // KB 绑定表是单独的一张关系表，这里一次性查出后按 nodeId 聚合。
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

    private String cacheKey(String agentId) {
        return CACHE_KEY_PREFIX + agentId + ":active";
    }
}
