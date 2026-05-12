package com.yulong.chatagent.conversation.event;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 本地异步 ChatEvent 监听器。
 * <p>
 * 当系统未启用 MQ 派发时，ChatEvent 会在当前进程内通过这里进入
 * ChatEventProcessor，再创建并运行目标 Agent。
 */
@Slf4j
@Component
@AllArgsConstructor
public class ChatEventListener {

    private final ChatEventProcessor chatEventProcessor;

    /**
     * 收到本地事件后执行 Agent 任务。
     *
     * @param event 已准备好的用户 turn 事件
     */
    @Async
    @EventListener
    public void handle(ChatEvent event) {
        try {
            // 本地路径刻意不自己实现一套业务逻辑，而是把真正处理统一收敛到 ChatEventProcessor。
            // 这样 Local 与 MQ 两条路径的核心行为、指标和失败处理都能保持一致。
            chatEventProcessor.process(event);
        } catch (Exception ex) {
            log.error("Failed to process chat event: agentId={}, sessionId={}, userMessageId={}",
                    event.getAgentId(), event.getSessionId(), event.getChatMessageId(), ex);
            // 本地异步路径没有 MQ 重试兜底，因此需要在这里直接补发用户可见的失败收尾。
            chatEventProcessor.publishFailure(event, ex);
        }
    }
}
