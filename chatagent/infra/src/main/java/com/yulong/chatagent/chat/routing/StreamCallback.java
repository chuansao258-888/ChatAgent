package com.yulong.chatagent.chat.routing;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

/**
 * 路由层统一使用的流式回调接口。
 *
 * <p>无论底层走原始 SSE 还是 Spring AI ChatClient.stream()，最终都会被转换成这些事件：
 * signal 表示模型已经有有效 chunk 到达，content 表示正文，thinking 表示推理/思考内容，
 * toolCalls 表示完整工具调用，complete/error 表示流结束。</p>
 */
public interface StreamCallback {
    /** 首包探测用的宽泛信号：收到一个有效 chunk，但不一定已经有正文 content。 */
    default void onSignal() {}

    /** 正文增量，调用方通常逐段追加显示或收集。 */
    void onContent(String content);

    /** 推理模型的思考内容增量，和最终正文分开传递。 */
    default void onThinking(String content) {}

    /** 已经拼完整的工具调用列表；分片拼接由底层适配器负责。 */
    default void onToolCalls(List<AssistantMessage.ToolCall> toolCalls) {}

    /** 当前流正常结束。 */
    void onComplete();

    /** 当前流失败。 */
    void onError(Throwable error);
}
