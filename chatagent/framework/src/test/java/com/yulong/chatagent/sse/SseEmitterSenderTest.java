package com.yulong.chatagent.sse;

import com.yulong.chatagent.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SseEmitterSenderTest {

    private SseEmitter emitter;
    private SseEmitterSender sender;

    @BeforeEach
    void setUp() {
        emitter = mock(SseEmitter.class);
        sender = new SseEmitterSender(emitter);
    }

    @Test
    void sendEvent_withName_sendsNamedEvent() throws Exception {
        sender.sendEvent("message", "hello");

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void sendEvent_nullName_sendsRawData() throws Exception {
        sender.sendEvent(null, "raw payload");

        verify(emitter).send("raw payload");
    }

    @Test
    void sendEvent_wrapsClientDisconnectIOExceptionWithoutAsyncErrorDispatch() throws Exception {
        doThrow(new IOException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        assertThatThrownBy(() -> sender.sendEvent("evt", "data"))
                .isInstanceOf(ServiceException.class)
                .hasMessage("Failed to send SSE event");

        verify(emitter, never()).complete();
        verify(emitter, never()).completeWithError(any());
    }

    @Test
    void sendEvent_wrapsNonDisconnectIOExceptionAndCompletesWithError() throws Exception {
        IOException error = new IOException("disk unavailable");
        doThrow(error).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        assertThatThrownBy(() -> sender.sendEvent("evt", "data"))
                .isInstanceOf(ServiceException.class)
                .hasMessage("Failed to send SSE event")
                .hasCause(error);

        verify(emitter, never()).complete();
        verify(emitter).completeWithError(error);
    }

    @Test
    void sendEvent_afterComplete_throwsServiceException() {
        sender.complete();

        assertThatThrownBy(() -> sender.sendEvent("evt", "data"))
                .isInstanceOf(ServiceException.class)
                .hasMessage("SSE already closed");
    }

    @Test
    void complete_callsEmitterComplete() {
        sender.complete();

        verify(emitter).complete();
    }

    @Test
    void complete_idempotent() {
        sender.complete();
        sender.complete();

        verify(emitter, times(1)).complete();
    }

    @Test
    void fail_marksClientDisconnectClosedWithoutAsyncErrorDispatch() {
        Throwable error = new IOException("connection reset");
        sender.fail(error);

        verify(emitter, never()).complete();
        verify(emitter, never()).completeWithError(any());
    }

    @Test
    void fail_nonClientErrorCompletesWithError() {
        Throwable error = new RuntimeException("oops");

        sender.fail(error);

        verify(emitter, never()).complete();
        verify(emitter).completeWithError(error);
    }

    @Test
    void fail_afterComplete_doesNotCallCompleteWithErrorAgain() {
        sender.complete();
        sender.fail(new IOException("connection reset"));

        verify(emitter, times(1)).complete();
        verify(emitter, never()).completeWithError(any());
    }

    @Test
    void fail_doesNotCompleteAgainAfterClientDisconnect() {
        doThrow(new IllegalStateException("Response not usable after response errors."))
                .when(emitter).complete();

        assertThatNoException().isThrownBy(() -> sender.fail(new IOException("broken pipe")));

        verify(emitter, never()).complete();
        verify(emitter, never()).completeWithError(any());
    }
}
