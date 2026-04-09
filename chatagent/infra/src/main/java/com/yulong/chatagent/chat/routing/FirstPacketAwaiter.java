package com.yulong.chatagent.chat.routing;

import lombok.Getter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class FirstPacketAwaiter {
    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicBoolean hasContent = new AtomicBoolean(false);
    private final AtomicBoolean eventFired = new AtomicBoolean(false);
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    public void markContent() { hasContent.set(true); fireEventOnce(); }
    public void markComplete() { fireEventOnce(); }
    public void markError(Throwable t) { error.set(t); fireEventOnce(); }

    private void fireEventOnce() {
        if (eventFired.compareAndSet(false, true)) latch.countDown();
    }

    public Result await(long timeout, TimeUnit unit) throws InterruptedException {
        boolean completed = latch.await(timeout, unit);
        if (error.get() != null) return Result.error(error.get());
        if (!completed) return Result.timeout();
        if (!hasContent.get()) return Result.noContent();
        return Result.success();
    }

    @Getter
    public static class Result {
        public enum Type { SUCCESS, ERROR, TIMEOUT, NO_CONTENT }
        private final Type type;
        private final Throwable error;
        private Result(Type type, Throwable error) { this.type = type; this.error = error; }
        public static Result success() { return new Result(Type.SUCCESS, null); }
        public static Result error(Throwable t) { return new Result(Type.ERROR, t); }
        public static Result timeout() { return new Result(Type.TIMEOUT, null); }
        public static Result noContent() { return new Result(Type.NO_CONTENT, null); }
        public boolean isSuccess() { return type == Type.SUCCESS; }
    }
}
