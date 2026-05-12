package com.yulong.chatagent.chat.routing;

import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;

/**
 * 将一次流式决策重新包装成“最终 ChatResponse + 流式事件回放”。
 *
 * <p>有些调用方需要流式模型能力，但最终仍要拿到同步形态的 ChatResponse。
 * RoutingLLMService.StreamingDecisionCollector 会把 content 拼成 ChatResponse，
 * 同时把 content/thinking 的分片保存在 events 里，方便调用方复用流式信息。</p>
 */
public record BufferedStreamingResponse(
        ChatResponse response,
        List<BufferedStreamEvent> events
) {

    /** 当前只缓存正文和 thinking；工具调用会进入 ChatResponse 的 AssistantMessage。 */
    public enum EventType {
        CONTENT,
        THINKING
    }

    /** 单条流式事件，text 保留原始分片文本，不做额外拼接。 */
    public record BufferedStreamEvent(
            EventType type,
            String text
    ) {
    }
}
