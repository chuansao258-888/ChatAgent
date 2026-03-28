package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.event.ConversationTurnCompletedEvent;
import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Publishes turn-completion events anchored by the latest persisted seq_no.
 */
@Component
@Slf4j
public class ConversationTurnCompletionPublisher {

    private final ChatMessageRepository chatMessageRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    public ConversationTurnCompletionPublisher(ChatMessageRepository chatMessageRepository,
                                               ApplicationEventPublisher applicationEventPublisher) {
        this.chatMessageRepository = chatMessageRepository;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public boolean publishCompletedTurn(String sessionId, String turnId) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(turnId)) {
            return false;
        }
        Long lastSeqNo = chatMessageRepository.findMaxSeqNoBySessionId(sessionId);
        if (lastSeqNo == null || lastSeqNo <= 0L) {
            log.warn("Skip turn-completed event without persisted anchor: sessionId={}, turnId={}", sessionId, turnId);
            return false;
        }
        applicationEventPublisher.publishEvent(new ConversationTurnCompletedEvent(sessionId, turnId, lastSeqNo));
        return true;
    }
}
