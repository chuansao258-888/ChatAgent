package com.yulong.chatagent.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface SseService {

    SseEmitter connect(String streamKey);

    void send(String streamKey, Object message);
}
