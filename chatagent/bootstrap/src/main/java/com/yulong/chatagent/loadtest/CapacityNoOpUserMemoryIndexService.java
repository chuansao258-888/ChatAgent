package com.yulong.chatagent.loadtest;

import com.yulong.chatagent.memory.application.UserMemoryIndexService;
import com.yulong.chatagent.memory.application.UserMemorySearchHit;

import java.util.List;

/**
 * Plain (unannotated) no-op {@link UserMemoryIndexService} used by
 * {@link CapacityTestMemoryConfiguration} under the {@code capacity-test} profile.
 *
 * <p>Duplicates the behavior of the production
 * {@code NoOpUserMemoryIndexService} without its {@code @ConditionalOnMissingBean}
 * annotation, which does not reliably activate when the Milvus bean is also
 * guarded. The capacity-test path does not exercise L3 memory recall.</p>
 */
class CapacityNoOpUserMemoryIndexService implements UserMemoryIndexService {

    @Override
    public void ensureCollection() {
        // No-op under capacity-test.
    }

    @Override
    public boolean upsertMemory(String memoryId, String userId, String type, String status,
                                String content, String tagsJson, float[] embedding) {
        return false;
    }

    @Override
    public boolean deleteMemory(String memoryId) { return false; }

    @Override
    public List<UserMemorySearchHit> search(String userId, float[] queryEmbedding, int topK) {
        return List.of();
    }
}
