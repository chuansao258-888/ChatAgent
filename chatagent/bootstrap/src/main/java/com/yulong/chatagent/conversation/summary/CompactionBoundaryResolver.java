package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Resolves the boundary between already summarized turns and the next rolling L2 batch.
 * <p>
 * V2 rolling behavior keeps raw runtime memory aligned with the L2 watermark:
 * when there are pending turns after {@code summarized_until_seq_no}, the resolver
 * selects the oldest {@code compactionBatchTurns} as the next compaction batch.
 * The policy decides whether the pending raw turn count has reached the configured
 * trigger threshold.
 */
@Component
public class CompactionBoundaryResolver {

    private final TurnBasedContextExtractor turnBasedContextExtractor;
    private final ChatSessionSummaryRepository chatSessionSummaryRepository;
    private final int compactionBatchTurns;

    public CompactionBoundaryResolver(TurnBasedContextExtractor turnBasedContextExtractor,
                                      ChatSessionSummaryRepository chatSessionSummaryRepository,
                                      @Value("${chatagent.memory.compaction.v2.compaction-batch-turns:20}") int compactionBatchTurns) {
        this.turnBasedContextExtractor = turnBasedContextExtractor;
        this.chatSessionSummaryRepository = chatSessionSummaryRepository;
        this.compactionBatchTurns = Math.max(compactionBatchTurns, 1);
    }

    public CompactionBoundary resolve(String sessionId, int l1WindowTurns) {
        ChatSessionSummaryDTO summary = chatSessionSummaryRepository.findBySessionId(sessionId);
        return resolve(sessionId, l1WindowTurns, summary);
    }

    CompactionBoundary resolve(String sessionId, int l1WindowTurns, ChatSessionSummaryDTO summary) {
        long summarizedUntilSeqNo = summary == null || summary.getSummarizedUntilSeqNo() == null
                ? 0L
                : summary.getSummarizedUntilSeqNo();

        int consecutiveFailures = summary == null || summary.getConsecutiveFailures() == null
                ? 0
                : summary.getConsecutiveFailures();

        boolean backoffActive = summary != null
                && summary.getNextRetryAt() != null
                && summary.getNextRetryAt().isAfter(LocalDateTime.now());

        List<AtomicConversationTurn> allTurns = turnBasedContextExtractor.extractAllTurns(sessionId);
        int totalTurns = allTurns.size();
        List<AtomicConversationTurn> unsummarizedTurns = allTurns.stream()
                .filter(turn -> turn.endSeqNo() > summarizedUntilSeqNo)
                .toList();
        int preservedTailTurns = Math.min(unsummarizedTurns.size(), Math.max(l1WindowTurns, 1));

        if (unsummarizedTurns.isEmpty()) {
            return new CompactionBoundary(
                    sessionId, summarizedUntilSeqNo, 0L,
                    totalTurns, preservedTailTurns, allTurns,
                    consecutiveFailures, backoffActive);
        }

        int batchSize = Math.min(compactionBatchTurns, unsummarizedTurns.size());
        long stableAnchorSeqNo = unsummarizedTurns.get(batchSize - 1).endSeqNo();

        return new CompactionBoundary(
                sessionId, summarizedUntilSeqNo, stableAnchorSeqNo,
                totalTurns, preservedTailTurns, allTurns,
                consecutiveFailures, backoffActive);
    }
}
