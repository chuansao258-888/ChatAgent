package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.event.ConversationTurnCompletedEvent;
import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * turn 完成事件发布器。
 * <p>
 * 它的核心职责不是“通知某个 turn 结束了”这么简单，而是：
 * <ul>
 *     <li>先为当前 session 找到一个稳定的持久化锚点 {@code lastSeqNo}；</li>
 *     <li>再把 {@code sessionId + turnId + lastSeqNo} 作为后台摘要链的输入。</li>
 * </ul>
 * 这样摘要系统就能按 seq_no 水位线做增量推进，而不是每次全量重扫整段会话。
 */
@Component
@Slf4j
public class ConversationTurnCompletionPublisher {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    public ConversationTurnCompletionPublisher(ChatMessageRepository chatMessageRepository,
                                               ChatSessionRepository chatSessionRepository,
                                               ApplicationEventPublisher applicationEventPublisher) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public boolean publishCompletedTurn(String sessionId, String turnId) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(turnId)) {
            return false;
        }
        Long turnSeq = chatMessageRepository.findTurnSeqBySessionIdAndTurnId(sessionId, turnId);
        chatMessageRepository.markTurnCompleted(sessionId, turnId);
        Long completedWatermark = chatSessionRepository.advanceCompletedTurnSeq(sessionId);
        log.debug("Advanced completed turn watermark: sessionId={}, turnId={}, lastCompletedTurnSeq={}",
                sessionId, turnId, completedWatermark);
        if (turnSeq != null && completedWatermark != null && completedWatermark < turnSeq) {
            log.info("Skip turn-completed event until earlier turns finish: sessionId={}, turnId={}, turnSeq={}, lastCompletedTurnSeq={}",
                    sessionId, turnId, turnSeq, completedWatermark);
            return false;
        }
        // 注意：这里不直接把“最后一条消息 ID”作为锚点，而是用 seq_no。
        // seq_no 更适合表达数据库内的稳定顺序区间。
        Long lastSeqNo = chatMessageRepository.findMaxSeqNoBySessionId(sessionId);
        if (lastSeqNo == null || lastSeqNo <= 0L) {
            log.warn("Skip turn-completed event without persisted anchor: sessionId={}, turnId={}", sessionId, turnId);
            return false;
        }
        applicationEventPublisher.publishEvent(new ConversationTurnCompletedEvent(sessionId, turnId, lastSeqNo));
        return true;
    }
}
