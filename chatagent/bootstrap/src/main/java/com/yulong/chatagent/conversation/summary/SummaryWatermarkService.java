package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 摘要水位线服务。
 * <p>
 * 它负责回答一个非常关键的问题：
 * “对于某个 session，当前还有哪一段 {@code seq_no} 区间尚未被 L2 摘要覆盖？”
 * <p>
 * 因而它管理的不是“某条消息有没有被摘要”，而是一个增量区间：
 * {@code (lastSummarizedSeqNo, anchorSeqNo]}。
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
        // 如果调用方没有显式传 anchor，就用当前 session 已持久化的最大 seq_no 作为锚点。
        Long latestSeqNo = chatMessageRepository.findMaxSeqNoBySessionId(sessionId);
        return resolvePendingRange(sessionId, latestSeqNo == null ? 0L : latestSeqNo);
    }

    public SummaryWatermarkRange resolvePendingRange(String sessionId, long anchorSeqNo) {
        ChatSessionSummaryDTO summary = chatSessionSummaryRepository.findBySessionId(sessionId);
        // lastSummarizedSeqNo 表示摘要系统已经稳定覆盖到哪里；
        // anchorSeqNo 表示“这次最多允许摘要到哪里”。
        long lastSummarizedSeqNo = summary == null || summary.getSummarizedUntilSeqNo() == null
                ? 0L
                : summary.getSummarizedUntilSeqNo();
        return new SummaryWatermarkRange(sessionId, lastSummarizedSeqNo, Math.max(anchorSeqNo, 0L));
    }

    public boolean isAnchorCovered(String sessionId, long anchorSeqNo) {
        // 如果 lastSummarizedSeqNo 已经推进到了 anchor 之后，
        // 当前这次 turn-completed 事件对摘要系统来说就没有新信息了。
        return resolvePendingRange(sessionId, anchorSeqNo).lastSummarizedSeqNo() >= anchorSeqNo;
    }

    public List<ChatMessageDTO> loadPendingMessages(String sessionId, long anchorSeqNo) {
        SummaryWatermarkRange range = resolvePendingRange(sessionId, anchorSeqNo);
        if (!range.hasPendingMessages()) {
            return List.of();
        }
        // 这里加载的是“未摘要区间内的消息”，不是整个会话历史。
        return chatMessageRepository.findBySessionIdAndSeqRange(
                sessionId,
                range.startExclusiveSeqNo(),
                range.endInclusiveSeqNo()
        );
    }
}
