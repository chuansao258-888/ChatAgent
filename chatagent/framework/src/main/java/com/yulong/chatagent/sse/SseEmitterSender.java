package com.yulong.chatagent.sse;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe wrapper around an {@link SseEmitter} that guards against sending
 * to an already-terminated stream.
 *
 * <p>An {@link AtomicBoolean} ensures {@link #complete()} and
 * {@link #fail(Throwable)} close the underlying emitter at most once,
 * preventing duplicate terminal signals when completion, timeout, and error
 * callbacks race.</p>
 */
@Slf4j
public class SseEmitterSender {

    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Wraps the given emitter for guarded access.
     *
     * @param emitter underlying SSE emitter
     */
    public SseEmitterSender(SseEmitter emitter) {
        this.emitter = emitter;
    }

    /**
     * Sends a named SSE event, or an anonymous event when {@code eventName} is {@code null}.
     *
     * @param eventName SSE event name, or {@code null} for an unnamed event
     * @param data      payload to deliver
     * @throws ServiceException if the stream is already closed or the send fails
     */
    public void sendEvent(String eventName, Object data) {
        if (closed.get()) {
            throw new ServiceException(BaseErrorCode.SERVICE_ERROR, "SSE already closed");
        }
        try {
            if (eventName == null) {
                emitter.send(data);
                return;
            }
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            fail(e);
            throw new ServiceException(BaseErrorCode.SERVICE_ERROR, "Failed to send SSE event", e);
        }
    }

    /**
     * Completes the stream. No-op when the emitter is already terminated.
     */
    public void complete() {
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    /**
     * Marks the stream as failed with the given cause and logs a warning.
     * No-op when the emitter is already terminated. Client-disconnect errors
     * are demoted to debug and do not attempt completion (the client is gone).
     *
     * @param throwable failure cause
     */
    public void fail(Throwable throwable) {
        if (SseClientDisconnects.isLikelyClientDisconnect(throwable)) {
            closed.set(true);
            log.debug("SSE client disconnected during send: {}", throwable.toString());
            return;
        }
        if (closed.compareAndSet(false, true)) {
            try {
                emitter.completeWithError(throwable);
            } catch (Exception completionFailure) {
                if (!SseClientDisconnects.isLikelyClientDisconnect(completionFailure)) {
                    log.warn("SSE error completion failed after send failure", completionFailure);
                }
            }
        }
        log.warn("SSE send failed", throwable);
    }
}
