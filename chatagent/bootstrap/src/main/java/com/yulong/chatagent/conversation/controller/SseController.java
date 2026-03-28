package com.yulong.chatagent.conversation.controller;

import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.sse.SseService;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Exposes server-sent event streams for chat-related realtime updates.
 */
@RestController
@RequestMapping("/api/sse")
@AllArgsConstructor
public class SseController {

    private final SseService sseService;
    private final ResourceAccessGuard resourceAccessGuard;

    /**
     * Opens an SSE connection scoped to one chat session.
     *
     * @param chatSessionId chat session identifier
     * @return live emitter bound to the session
     */
    @RequestMapping(value = "/connect/{chatSessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@PathVariable String chatSessionId) {
        resourceAccessGuard.assertCanReadSession(UserContext.requireUser(), chatSessionId);
        return sseService.connect(chatSessionId);
    }
}
