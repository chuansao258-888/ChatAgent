package com.yulong.chatagent.chat.routing;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

public interface StreamCallback {
    default void onSignal() {}
    void onContent(String content);
    default void onThinking(String content) {}
    default void onToolCalls(List<AssistantMessage.ToolCall> toolCalls) {}
    void onComplete();
    void onError(Throwable error);
}
