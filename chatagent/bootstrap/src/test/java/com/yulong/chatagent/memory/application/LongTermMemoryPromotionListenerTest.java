package com.yulong.chatagent.memory.application;

import com.yulong.chatagent.conversation.event.LongTermMemoryPromotionRequestedEvent;
import com.yulong.chatagent.conversation.summary.AtomicConversationTurn;
import com.yulong.chatagent.conversation.summary.SummaryWatermarkRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LongTermMemoryPromotionListenerTest {

    @Mock
    private LongTermMemoryPromotionService promotionService;

    @Test
    void shouldDelegateToPromotionService() {
        LongTermMemoryPromotionListener listener = new LongTermMemoryPromotionListener(promotionService);
        SummaryWatermarkRange range = new SummaryWatermarkRange("session-1", 4L, 12L);
        List<AtomicConversationTurn> turns = List.of(
                new AtomicConversationTurn("turn-1", 5L, 8L, List.of("hello"), "hi"));
        LongTermMemoryPromotionRequestedEvent event =
                new LongTermMemoryPromotionRequestedEvent("session-1", range, turns);

        listener.handle(event);

        verify(promotionService).promote("session-1", range, turns);
    }
}
