package com.yulong.chatagent.support.persistence.adapter.intent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.IntentNodeLevel;
import com.yulong.chatagent.intent.model.IntentNodeStatus;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.intent.port.IntentNodeRepository;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import com.yulong.chatagent.support.persistence.entity.IntentNode;
import com.yulong.chatagent.support.persistence.mapper.IntentNodeMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis-backed repository for intent tree nodes.
 */
@Repository
public class MyBatisIntentNodeRepository implements IntentNodeRepository {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final IntentNodeMapper intentNodeMapper;
    private final ObjectMapper objectMapper;

    public MyBatisIntentNodeRepository(IntentNodeMapper intentNodeMapper,
                                       ObjectMapper objectMapper) {
        this.intentNodeMapper = intentNodeMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<IntentNodeDTO> findByAgentIdAndVersion(String agentId, int version) {
        List<IntentNodeDTO> result = new ArrayList<>();
        for (IntentNode node : intentNodeMapper.selectByAgentIdAndVersion(agentId, version)) {
            result.add(toDTO(node));
        }
        return result;
    }

    @Override
    public IntentNodeDTO findById(String id) {
        return toDTO(intentNodeMapper.selectById(id));
    }

    @Override
    public boolean save(IntentNodeDTO intentNode) {
        IntentNode entity = toEntity(intentNode);
        boolean saved = intentNodeMapper.insert(entity) > 0;
        if (saved && intentNode != null) {
            intentNode.setId(entity.getId());
        }
        return saved;
    }

    @Override
    public boolean saveAll(List<IntentNodeDTO> intentNodes) {
        if (intentNodes == null || intentNodes.isEmpty()) {
            return true;
        }
        List<IntentNode> entities = new ArrayList<>();
        for (IntentNodeDTO intentNode : intentNodes) {
            entities.add(toEntity(intentNode));
        }
        return intentNodeMapper.batchInsert(entities) == entities.size();
    }

    @Override
    public boolean update(IntentNodeDTO intentNode) {
        return intentNodeMapper.updateById(toEntity(intentNode)) > 0;
    }

    @Override
    public boolean deleteByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return true;
        }
        return intentNodeMapper.deleteByIds(ids) >= 0;
    }

    @Override
    public List<Integer> findPublishedVersions(String agentId) {
        return intentNodeMapper.selectPublishedVersionsByAgentId(agentId);
    }

    @Override
    public Integer findMaxVersion(String agentId) {
        return intentNodeMapper.selectMaxVersionByAgentId(agentId);
    }

    private IntentNodeDTO toDTO(IntentNode node) {
        if (node == null) {
            return null;
        }
        try {
            return IntentNodeDTO.builder()
                    .id(node.getId())
                    .agentId(node.getAgentId())
                    .parentId(node.getParentId())
                    .version(node.getVersion())
                    .status(node.getStatus() == null ? null : IntentNodeStatus.valueOf(node.getStatus()))
                    .nodeLevel(node.getNodeLevel() == null ? null : IntentNodeLevel.valueOf(node.getNodeLevel()))
                    .name(node.getName())
                    .description(node.getDescription())
                    .examples(readStringList(node.getExamples()))
                    .intentKind(node.getIntentKind() == null ? null : IntentKind.valueOf(node.getIntentKind()))
                    .scopePolicy(node.getScopePolicy() == null ? null : ScopePolicy.valueOf(node.getScopePolicy()))
                    .allowedTools(readStringList(node.getAllowedTools()))
                    .systemPromptOverride(node.getSystemPromptOverride())
                    .enabled(node.getEnabled())
                    .sortOrder(node.getSortOrder())
                    .createdAt(node.getCreatedAt())
                    .updatedAt(node.getUpdatedAt())
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize intent node", e);
        }
    }

    private IntentNode toEntity(IntentNodeDTO dto) {
        if (dto == null) {
            return null;
        }
        try {
            return IntentNode.builder()
                    .id(dto.getId())
                    .agentId(dto.getAgentId())
                    .parentId(dto.getParentId())
                    .version(dto.getVersion())
                    .status(dto.getStatus() == null ? null : dto.getStatus().name())
                    .nodeLevel(dto.getNodeLevel() == null ? null : dto.getNodeLevel().name())
                    .name(dto.getName())
                    .description(dto.getDescription())
                    .examples(writeStringList(dto.getExamples()))
                    .intentKind(dto.getIntentKind() == null ? null : dto.getIntentKind().name())
                    .scopePolicy(dto.getScopePolicy() == null ? null : dto.getScopePolicy().name())
                    .allowedTools(writeStringList(dto.getAllowedTools()))
                    .systemPromptOverride(dto.getSystemPromptOverride())
                    .enabled(dto.getEnabled())
                    .sortOrder(dto.getSortOrder())
                    .createdAt(dto.getCreatedAt())
                    .updatedAt(dto.getUpdatedAt())
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize intent node", e);
        }
    }

    private List<String> readStringList(String json) throws JsonProcessingException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, STRING_LIST_TYPE);
    }

    private String writeStringList(List<String> values) throws JsonProcessingException {
        return objectMapper.writeValueAsString(values == null ? List.of() : values);
    }
}
