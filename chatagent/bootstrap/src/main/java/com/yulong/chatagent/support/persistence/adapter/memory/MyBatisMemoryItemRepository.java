package com.yulong.chatagent.support.persistence.adapter.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.memory.port.MemoryItemRepository;
import com.yulong.chatagent.support.dto.MemoryItemDTO;
import com.yulong.chatagent.support.persistence.mapper.MemoryItemMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * MyBatis-backed repository for long-term memory items.
 */
@Repository
public class MyBatisMemoryItemRepository implements MemoryItemRepository {

    private static final TypeReference<List<String>> TAGS_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> SOURCE_MAP_TYPE = new TypeReference<>() {};

    private final MemoryItemMapper memoryItemMapper;
    private final ObjectMapper objectMapper;

    public MyBatisMemoryItemRepository(MemoryItemMapper memoryItemMapper,
                                       ObjectMapper objectMapper) {
        this.memoryItemMapper = memoryItemMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public MemoryItemDTO upsert(MemoryItemDTO item) {
        if (item.getStatus() == null) {
            item.setStatus("active");
        }
        if (item.getIndexStatus() == null) {
            item.setIndexStatus("pending");
        }
        item.setTagsJson(writeJson(item.getTags()));
        item.setSourceJson(writeJson(item.getSource()));
        item.setUpdatedAt(LocalDateTime.now());

        memoryItemMapper.upsertOnConflict(item);
        // After upsert, read back to get the canonical id and timestamps.
        // For the common case we can find by the unique hash key.
        return findByUserIdTypeAndHash(item.getUserId(), item.getType(), item.getContentHash());
    }

    @Override
    public MemoryItemDTO findById(String id) {
        MemoryItemDTO dto = memoryItemMapper.selectById(id);
        if (dto != null) {
            deserializeJsonFields(dto);
        }
        return dto;
    }

    @Override
    public List<MemoryItemDTO> findByUserIdAndStatus(String userId, String status) {
        List<MemoryItemDTO> items = memoryItemMapper.selectByUserIdAndStatus(userId, status);
        items.forEach(this::deserializeJsonFields);
        return items;
    }

    @Override
    public boolean updateIndexStatus(String id, String indexStatus) {
        return memoryItemMapper.updateIndexStatus(id, indexStatus) > 0;
    }

    @Override
    public boolean updateStatus(String id, String status) {
        return memoryItemMapper.updateStatus(id, status) > 0;
    }

    private MemoryItemDTO findByUserIdTypeAndHash(String userId, String type, String contentHash) {
        MemoryItemDTO dto = memoryItemMapper.selectByUserAndTypeAndHash(userId, type, contentHash);
        if (dto != null) {
            deserializeJsonFields(dto);
        }
        return dto;
    }

    private void deserializeJsonFields(MemoryItemDTO dto) {
        dto.setTags(readJson(dto.getTagsJson(), TAGS_LIST_TYPE));
        dto.setSource(readJson(dto.getSourceJson(), SOURCE_MAP_TYPE));
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize memory item JSON", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize memory item JSON", e);
        }
    }
}
