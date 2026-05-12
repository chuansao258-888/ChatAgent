package com.yulong.chatagent.chat.routing;

import lombok.Getter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    // hasContent 表示是否真的见到有效内容/信号；只完成但没有内容会被判定为 NO_CONTENT。
    private final AtomicBoolean hasContent = new AtomicBoolean(false);
    // 防止多个流式事件重复 countDown，保证首包结果只被第一次事件决定。
    private final AtomicBoolean eventFired = new AtomicBoolean(false);
    // 如果首包阶段先收到错误，await 返回 ERROR，并保留原始异常。
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    /** 收到有效 chunk/content/thinking/tool call 时调用，表示首包探测成功。 */
    public void markContent() { hasContent.set(true); fireEventOnce(); }

    /** 流在首包前正常结束时调用，后续 await 会返回 NO_CONTENT。 */
    public void markComplete() { fireEventOnce(); }

    /** 流在首包阶段失败时调用，后续 await 会返回 ERROR。 */
    public void markError(Throwable t) { error.set(t); fireEventOnce(); }

    private void fireEventOnce() {
        if (eventFired.compareAndSet(false, true)) latch.countDown();
    }

    public Result await(long timeout, TimeUnit unit) throws InterruptedException {
        // 等待首个事件到达，或等待超时。completed=false 表示这段时间内完全没有事件。
        boolean completed = latch.await(timeout, unit);
        if (error.get() != null) return Result.error(error.get());
        if (!completed) return Result.timeout();
        if (!hasContent.get()) return Result.noContent();
        return Result.success();
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
