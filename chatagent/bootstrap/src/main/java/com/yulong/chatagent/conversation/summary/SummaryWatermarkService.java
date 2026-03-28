package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Resolves the seq_no watermark window that still needs summarization.
 */
@Service
public class SummaryWatermarkService {

    private final ChatSessionSummaryRepository chatSessionSummaryRepository;
    private final ChatMessageRepository chatMessageRepository;

    public SummaryWatermarkService(ChatSessionSummaryRepository chatSessionSummaryRepository,
                                   ChatMessageRepository chatMessageRepository) {
        this.chatSessionSummaryRepository = chatSessionSummaryRepository;
        this.chatMessageRepository = chatMessageRepository;
    }

    public SummaryWatermarkRange resolvePendingRange(String sessionId) {
        Long latestSeqNo = chatMessageRepository.findMaxSeqNoBySessionId(sessionId);
        return resolvePendingRange(sessionId, latestSeqNo == null ? 0L : latestSeqNo);
    }

    public SummaryWatermarkRange resolvePendingRange(String sessionId, long anchorSeqNo) {
        ChatSessionSummaryDTO summary = chatSessionSummaryRepository.findBySessionId(sessionId);
        long lastSummarizedSeqNo = summary == null || summary.getLastSeqNo() == null
                ? 0L
                : summary.getLastSeqNo();
        return new SummaryWatermarkRange(sessionId, lastSummarizedSeqNo, Math.max(anchorSeqNo, 0L));
    }

    public boolean isAnchorCovered(String sessionId, long anchorSeqNo) {
        return resolvePendingRange(sessionId, anchorSeqNo).lastSummarizedSeqNo() >= anchorSeqNo;
    }

    public List<ChatMessageDTO> loadPendingMessages(String sessionId, long anchorSeqNo) {
        SummaryWatermarkRange range = resolvePendingRange(sessionId, anchorSeqNo);
        if (!range.hasPendingMessages()) {
            return List.of();
        }
        return chatMessageRepository.findBySessionIdAndSeqRange(
                sessionId,
                range.startExclusiveSeqNo(),
                range.endInclusiveSeqNo()
        );
    }
}
