package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Resolves the boundary between stable (L2-eligible) turns and the preserved L1 tail.
 * <p>
 * Stable turns are those outside the most recent {@code l1WindowTurns} turns.
 * The stable anchor is the endSeqNo of the newest stable turn.
 * L2 compaction should only summarize up to this anchor, never into the L1 tail.
 */
@Component
public class CompactionBoundaryResolver {

    private final TurnBasedContextExtractor turnBasedContextExtractor;
    private final ChatSessionSummaryRepository chatSessionSummaryRepository;

    public CompactionBoundaryResolver(TurnBasedContextExtractor turnBasedContextExtractor,
                                      ChatSessionSummaryRepository chatSessionSummaryRepository) {
        this.turnBasedContextExtractor = turnBasedContextExtractor;
        this.chatSessionSummaryRepository = chatSessionSummaryRepository;
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
        int preservedTailTurns = Math.min(totalTurns, l1WindowTurns);

        if (totalTurns <= l1WindowTurns) {
            return new CompactionBoundary(
                    sessionId, summarizedUntilSeqNo, 0L,
                    totalTurns, preservedTailTurns, allTurns,
                    consecutiveFailures, backoffActive);
        }

        int stableCount = totalTurns - l1WindowTurns;
        long stableAnchorSeqNo = allTurns.get(stableCount - 1).endSeqNo();

        return new CompactionBoundary(
                sessionId, summarizedUntilSeqNo, stableAnchorSeqNo,
                totalTurns, preservedTailTurns, allTurns,
                consecutiveFailures, backoffActive);
    }
}
