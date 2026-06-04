package com.yulong.chatagent.memory.application;

import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.conversation.summary.AtomicConversationTurn;
import com.yulong.chatagent.conversation.summary.SummaryWatermarkRange;
import com.yulong.chatagent.memory.port.MemoryExtractionLogRepository;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.support.dto.MemoryExtractionLogDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Handles L3 long-term memory promotion after L2 summarization.
 *
 * <p>For each completed L2 batch, this service:
 * <ol>
 *     <li>Resolves the user from the session (skips if no user).</li>
 *     <li>Checks extraction log idempotency — duplicate ranges are skipped.</li>
 *     <li>Records the extraction log as processing.</li>
 *     <li>Extracts long-term memories from the raw turns (Phase 3 fills this in).</li>
 *     <li>Marks the extraction log as completed or failed.</li>
 * </ol>
 *
 * <p>L3 failures are fully isolated from L2: any exception is caught and logged
 * without rethrowing, so L2 summary success is never affected.
 */
@Service
@Slf4j
public class LongTermMemoryPromotionService {

    private final ChatSessionRepository chatSessionRepository;
    private final MemoryExtractionLogRepository extractionLogRepository;

    public LongTermMemoryPromotionService(ChatSessionRepository chatSessionRepository,
                                          MemoryExtractionLogRepository extractionLogRepository) {
        this.chatSessionRepository = chatSessionRepository;
        this.extractionLogRepository = extractionLogRepository;
    }

    /**
     * Promotes long-term memories from the raw turns of a completed L2 batch.
     * Exceptions are caught internally so L2 summarization is never affected.
     *
     * @param sessionId the session that triggered L2 compression
     * @param range     the seq_no range that was summarized
     * @param turns     the raw turns that L2 compressed
     */
    public void promote(String sessionId, SummaryWatermarkRange range, List<AtomicConversationTurn> turns) {
        try {
            doPromote(sessionId, range, turns);
        } catch (Exception e) {
            log.warn("L3 memory promotion failed, L2 summary is unaffected: sessionId={}, range={}:{}, error={}",
                    sessionId, range.startExclusiveSeqNo(), range.endInclusiveSeqNo(), e.getMessage());
        }
    }

    private void doPromote(String sessionId, SummaryWatermarkRange range, List<AtomicConversationTurn> turns) {
        ChatSessionDTO session = chatSessionRepository.findById(sessionId);
        if (session == null || session.getUserId() == null) {
            log.debug("L3 promotion skipped: session has no user: sessionId={}", sessionId);
            return;
        }
        String userId = session.getUserId();

        // Idempotency: skip if this range was already extracted.
        MemoryExtractionLogDTO existingLog = extractionLogRepository.findByRange(
                sessionId, range.startExclusiveSeqNo() + 1, range.endInclusiveSeqNo());
        if (existingLog != null) {
            log.debug("L3 promotion skipped: range already processed: sessionId={}, logStatus={}",
                    sessionId, existingLog.getStatus());
            return;
        }

        // Record extraction as processing.
        MemoryExtractionLogDTO extractionLog = extractionLogRepository.insert(MemoryExtractionLogDTO.builder()
                .userId(userId)
                .sessionId(sessionId)
                .seqStartNo(range.startExclusiveSeqNo() + 1)
                .seqEndNo(range.endInclusiveSeqNo())
                .status("processing")
                .build());

        try {
            // Phase 3 will add the actual extractor call here.
            // For now, this is a no-op that marks the extraction as completed.
            log.info("L3 memory promotion completed (extractor pending Phase 3): sessionId={}, userId={}, turns={}",
                    sessionId, userId, turns.size());

            extractionLogRepository.updateStatus(extractionLog.getId(), "completed", null);
        } catch (Exception e) {
            log.warn("L3 extraction failed, marking log: sessionId={}, error={}", sessionId, e.getMessage());
            extractionLogRepository.updateStatus(extractionLog.getId(), "failed", abbreviate(e));
        }
    }

    private String abbreviate(Exception e) {
        String message = e == null ? null : e.getMessage();
        if (message == null || message.isBlank()) {
            return e == null ? "Unknown error" : e.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
