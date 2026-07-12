package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.conversation.port.ChatSessionSummarySegmentRepository;
import com.yulong.chatagent.memory.port.MemoryPromotionJobRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import com.yulong.chatagent.support.dto.ChatSessionSummarySegmentDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the short atomic commit after summary model work has completed. */
@Service
public class MemoryCompactionCommitService {

    public enum CommitOutcome {
        COMMITTED,
        STALE_WATERMARK
    }

    private final ChatSessionSummaryRepository summaryRepository;
    private final ChatSessionSummarySegmentRepository segmentRepository;
    private final MemoryPromotionJobRepository promotionJobRepository;
    private final boolean l3Enabled;

    public MemoryCompactionCommitService(ChatSessionSummaryRepository summaryRepository,
                                         ChatSessionSummarySegmentRepository segmentRepository,
                                         MemoryPromotionJobRepository promotionJobRepository,
                                         @Value("${chatagent.memory.l3.enabled:true}") boolean l3Enabled) {
        this.summaryRepository = summaryRepository;
        this.segmentRepository = segmentRepository;
        this.promotionJobRepository = promotionJobRepository;
        this.l3Enabled = l3Enabled;
    }

    @Transactional
    public CommitOutcome commit(long expectedWatermark,
                                ChatSessionSummarySegmentDTO segment,
                                ChatSessionSummaryDTO updatedSummary) {
        ChatSessionSummaryDTO current = summaryRepository.findBySessionId(segment.getSessionId());
        long currentWatermark = current == null || current.getSummarizedUntilSeqNo() == null
                ? 0L : current.getSummarizedUntilSeqNo();
        if (currentWatermark != expectedWatermark) {
            return CommitOutcome.STALE_WATERMARK;
        }

        // ON CONFLICT is intentionally tolerated to repair a legacy partial segment write.
        boolean segmentInserted = segmentRepository.insert(segment);
        if (!segmentInserted) {
            updatedSummary.setSegmentCount(current == null || current.getSegmentCount() == null
                    ? 0 : current.getSegmentCount());
        }
        if (!summaryRepository.saveOrUpdate(updatedSummary)) {
            throw new IllegalStateException("Failed to persist memory compaction summary");
        }
        if (l3Enabled) {
            // A duplicate range means durable work already exists, which is idempotent success.
            promotionJobRepository.insertPendingForSession(
                    segment.getSessionId(), segment.getSeqStartNo(), segment.getSeqEndNo());
        }
        return CommitOutcome.COMMITTED;
    }
}
