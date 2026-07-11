package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.conversation.application.SessionRunCoordinator;
import com.yulong.chatagent.conversation.application.SessionRunProperties;
import com.yulong.chatagent.ratelimit.capacity.AgentRunCapacityLimiter;
import com.yulong.chatagent.ratelimit.capacity.CapacityGateResult;
import com.yulong.chatagent.ratelimit.capacity.NoopPermit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class ChatEventListenerTest {

    @Test
    void slowFirstTurnCompletesBeforeRapidSecondTurnStarts() throws Exception {
        ChatEventProcessor processor = mock(ChatEventProcessor.class);
        AgentRunCapacityLimiter limiter = mock(AgentRunCapacityLimiter.class);
        when(limiter.tryAcquireLocalCapOnly()).thenReturn(CapacityGateResult.proceed(NoopPermit.instance()));
        SessionRunProperties properties = new SessionRunProperties();
        properties.setLocalAcquireTimeoutMs(2_000);
        ChatEventListener listener = new ChatEventListener(processor, limiter, new SessionRunCoordinator(properties));
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            ChatEvent event = invocation.getArgument(0);
            order.add("start-" + event.getTurnId());
            if ("turn-1".equals(event.getTurnId())) {
                firstEntered.countDown();
                releaseFirst.await(1, TimeUnit.SECONDS);
            }
            order.add("end-" + event.getTurnId());
            return null;
        }).when(processor).process(org.mockito.ArgumentMatchers.any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> listener.handle(event("turn-1")));
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> listener.handle(event("turn-2")));
            Thread.sleep(50);
            assertThat(order).containsExactly("start-turn-1");
            releaseFirst.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly("start-turn-1", "end-turn-1", "start-turn-2", "end-turn-2");
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private static ChatEvent event(String turnId) {
        return new ChatEvent("agent-1", "session-1", turnId, "message-" + turnId,
                "hello", 0, null, "hello");
    }
}
