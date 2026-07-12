package com.yulong.chatagent.chat.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FirstPacketAwaiterTest {

    @Test
    void shouldKeepSuccessWhenLaterErrorArrives() throws Exception {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
        awaiter.markContent();
        awaiter.markError(new IllegalStateException("late"));

        FirstPacketAwaiter.Result result = awaiter.await(1, TimeUnit.SECONDS);

        assertThat(result.getType()).isEqualTo(FirstPacketAwaiter.Result.Type.SUCCESS);
        assertThat(result.getError()).isNull();
    }

    @Test
    void shouldKeepFirstErrorAndThrowableWhenLaterContentArrives() throws Exception {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
        IllegalStateException failure = new IllegalStateException("first");
        awaiter.markError(failure);
        awaiter.markContent();

        FirstPacketAwaiter.Result result = awaiter.await(1, TimeUnit.SECONDS);

        assertThat(result.getType()).isEqualTo(FirstPacketAwaiter.Result.Type.ERROR);
        assertThat(result.getError()).isSameAs(failure);
    }

    @Test
    void shouldKeepNoContentWhenCompletionWins() throws Exception {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
        awaiter.markComplete();
        awaiter.markContent();

        assertThat(awaiter.await(1, TimeUnit.SECONDS).getType())
                .isEqualTo(FirstPacketAwaiter.Result.Type.NO_CONTENT);
    }

    @Test
    void shouldKeepTimeoutWhenCallbackArrivesLate() throws Exception {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();

        FirstPacketAwaiter.Result timeout = awaiter.await(1, TimeUnit.MILLISECONDS);
        awaiter.markContent();

        assertThat(timeout.getType()).isEqualTo(FirstPacketAwaiter.Result.Type.TIMEOUT);
        assertThat(awaiter.await(0, TimeUnit.MILLISECONDS)).isSameAs(timeout);
    }

    @Test
    void concurrentCallbacksShouldPublishOneImmutableOutcome() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int iteration = 0; iteration < 100; iteration++) {
                FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
                CountDownLatch ready = new CountDownLatch(3);
                CountDownLatch start = new CountDownLatch(1);
                RuntimeException failure = new RuntimeException("race-" + iteration);

                List<Future<?>> futures = List.of(
                        submit(executor, ready, start, awaiter::markContent),
                        submit(executor, ready, start, awaiter::markComplete),
                        submit(executor, ready, start, () -> awaiter.markError(failure))
                );
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }

                FirstPacketAwaiter.Result first = awaiter.await(1, TimeUnit.SECONDS);
                awaiter.markContent();
                awaiter.markComplete();
                awaiter.markError(new RuntimeException("late"));

                assertThat(first.getType()).isIn(
                        FirstPacketAwaiter.Result.Type.SUCCESS,
                        FirstPacketAwaiter.Result.Type.NO_CONTENT,
                        FirstPacketAwaiter.Result.Type.ERROR);
                assertThat(awaiter.await(0, TimeUnit.MILLISECONDS)).isSameAs(first);
                if (first.getType() == FirstPacketAwaiter.Result.Type.ERROR) {
                    assertThat(first.getError()).isSameAs(failure);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static Future<?> submit(ExecutorService executor,
                                    CountDownLatch ready,
                                    CountDownLatch start,
                                    Runnable callback) {
        return executor.submit(() -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to start callback race");
            }
            callback.run();
            return null;
        });
    }
}
