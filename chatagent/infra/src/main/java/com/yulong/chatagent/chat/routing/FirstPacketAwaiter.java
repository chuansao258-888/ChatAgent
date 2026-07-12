package com.yulong.chatagent.chat.routing;

import lombok.Getter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 首包等待器。
 *
 * <p>routeAndStream 发起某个候选模型的流式请求后，会用它等待“第一个有效事件”。
 * ProbeBufferingCallback 收到 onSignal/onContent/onThinking/onToolCalls/onComplete/onError 后，
 * 会调用 markContent/markComplete/markError 唤醒这里的 CountDownLatch。</p>
 */
public class FirstPacketAwaiter {
    // latch 只需要释放一次：首个内容、完成或错误任意一个事件到达，都说明探测有结果了。
    private final CountDownLatch latch = new CountDownLatch(1);
    // 一个不可变结果同时表达类型和异常；唯一 CAS 决定首事件，避免多个原子字段互相覆盖。
    private final AtomicReference<Result> outcome = new AtomicReference<>();

    /** 收到有效 chunk/content/thinking/tool call 时调用，表示首包探测成功。 */
    public void markContent() { completeOnce(Result.success()); }

    /** 流在首包前正常结束时调用，后续 await 会返回 NO_CONTENT。 */
    public void markComplete() { completeOnce(Result.noContent()); }

    /** 流在首包阶段失败时调用，后续 await 会返回 ERROR。 */
    public void markError(Throwable t) { completeOnce(Result.error(t)); }

    private void completeOnce(Result result) {
        if (outcome.compareAndSet(null, result)) {
            latch.countDown();
        }
    }

    public Result await(long timeout, TimeUnit unit) throws InterruptedException {
        Result completed = outcome.get();
        if (completed != null) {
            return completed;
        }
        if (!latch.await(timeout, unit)) {
            // TIMEOUT 也参与同一个 CAS；若回调恰好先赢，返回那个真实首事件。
            completeOnce(Result.timeout());
        }
        return outcome.get();
    }

    @Getter
    public static class Result {
        /** SUCCESS=有有效首包，ERROR=首包阶段出错，TIMEOUT=无事件超时，NO_CONTENT=完成但没有内容。 */
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
