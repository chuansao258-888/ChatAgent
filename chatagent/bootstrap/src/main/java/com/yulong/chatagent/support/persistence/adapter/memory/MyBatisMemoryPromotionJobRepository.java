package com.yulong.chatagent.support.persistence.adapter.memory;

import com.yulong.chatagent.memory.port.MemoryPromotionJobRepository;
import com.yulong.chatagent.support.dto.MemoryPromotionJobDTO;
import com.yulong.chatagent.support.persistence.mapper.MemoryPromotionJobMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Repository
public class MyBatisMemoryPromotionJobRepository implements MemoryPromotionJobRepository {
    private final MemoryPromotionJobMapper mapper;

    public MyBatisMemoryPromotionJobRepository(MemoryPromotionJobMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean insertPendingForSession(String sessionId, long seqStartNo, long seqEndNo) {
        return mapper.insertPendingForSession(sessionId, seqStartNo, seqEndNo) > 0;
    }

    @Override
    @Transactional
    public MemoryPromotionJobDTO claimNextDue(LocalDateTime now) {
        return mapper.claimNextDue(now);
    }

    @Override
    public boolean markCompleted(String id) {
        return mapper.markCompleted(id) > 0;
    }

    @Override
    public boolean markRetry(String id, LocalDateTime nextAttemptAt, String lastError) {
        return mapper.markRetry(id, nextAttemptAt, lastError) > 0;
    }

    @Override
    public boolean markFailed(String id, String lastError) {
        return mapper.markFailed(id, lastError) > 0;
    }

    @Override
    public int reclaimStale(LocalDateTime processingStartedBefore) {
        return mapper.reclaimStale(processingStartedBefore);
    }

    @Override
    public long countBacklog() {
        return mapper.countBacklog();
    }
}
