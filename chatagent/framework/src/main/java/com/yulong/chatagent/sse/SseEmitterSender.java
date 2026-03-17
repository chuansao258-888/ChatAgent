package com.yulong.chatagent.sse;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class SseEmitterSender {

    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SseEmitterSender(SseEmitter emitter) {
        this.emitter = emitter;
    }

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

    public void complete() {
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    public void fail(Throwable throwable) {
        if (closed.compareAndSet(false, true)) {
            emitter.completeWithError(throwable);
        }
        log.warn("SSE send failed", throwable);
    }
}
