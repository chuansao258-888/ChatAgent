package com.yulong.chatagent.memory.application;

import com.yulong.chatagent.conversation.event.LongTermMemoryPromotionRequestedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Listens for L3 memory promotion events and delegates to the promotion service.
 *
 * <p>Runs on {@code l3Executor} so that L3 extractor LLM calls never block
 * the {@code summaryExecutor} used by L2 summarization.
 */
@Component
@Slf4j
public class LongTermMemoryPromotionListener {

    private final LongTermMemoryPromotionService promotionService;

    public LongTermMemoryPromotionListener(LongTermMemoryPromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @Async("l3Executor")
    @EventListener
    public void handle(LongTermMemoryPromotionRequestedEvent event) {
        log.debug("L3 promotion received: sessionId={}, turns={}", event.sessionId(), event.turns().size());
        promotionService.promote(event.sessionId(), event.range(), event.turns());
    }
}
