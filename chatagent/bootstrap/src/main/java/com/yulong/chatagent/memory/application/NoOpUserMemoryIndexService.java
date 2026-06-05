package com.yulong.chatagent.memory.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * No-op fallback for the user-memory Milvus index service.
 *
 * <p>Active when either Milvus or L3 memory is disabled. Returns empty recall hits
 * and signals index failure so callers can mark {@code index_status=failed}.
 */
@Service
@ConditionalOnMissingBean(UserMemoryIndexService.class)
@Slf4j
public class NoOpUserMemoryIndexService implements UserMemoryIndexService {

    @Override
    public void ensureCollection() {
        // No-op: Milvus is unavailable or L3 is disabled.
    }

    @Override
    public boolean upsertMemory(String memoryId, String userId, String type, String status,
                                String content, String tagsJson, float[] embedding) {
        log.debug("User-memory Milvus upsert skipped (service unavailable): memoryId={}", memoryId);
        return false;
    }

    @Override
    public List<UserMemorySearchHit> search(String userId, float[] queryEmbedding, int topK) {
        return List.of();
    }
}
