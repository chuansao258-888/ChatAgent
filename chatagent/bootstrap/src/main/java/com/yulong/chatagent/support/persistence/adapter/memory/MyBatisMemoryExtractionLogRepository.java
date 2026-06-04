package com.yulong.chatagent.support.persistence.adapter.memory;

import com.yulong.chatagent.memory.port.MemoryExtractionLogRepository;
import com.yulong.chatagent.support.dto.MemoryExtractionLogDTO;
import com.yulong.chatagent.support.persistence.mapper.MemoryExtractionLogMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * MyBatis-backed repository for memory extraction idempotency logs.
 */
@Repository
public class MyBatisMemoryExtractionLogRepository implements MemoryExtractionLogRepository {

    private final MemoryExtractionLogMapper mapper;

    public MyBatisMemoryExtractionLogRepository(MemoryExtractionLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public MemoryExtractionLogDTO findByRange(String sessionId, long seqStartNo, long seqEndNo) {
        return mapper.selectByRange(sessionId, seqStartNo, seqEndNo);
    }

    @Override
    public MemoryExtractionLogDTO insert(MemoryExtractionLogDTO log) {
        if (log.getCreatedAt() == null) {
            log.setCreatedAt(LocalDateTime.now());
        }
        log.setUpdatedAt(LocalDateTime.now());
        mapper.insert(log);
        return log;
    }

    @Override
    public boolean updateStatus(String id, String status, String errorMessage) {
        return mapper.updateStatus(id, status, errorMessage) > 0;
    }
}
