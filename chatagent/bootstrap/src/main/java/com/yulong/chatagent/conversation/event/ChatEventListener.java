package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.conversation.application.SessionRunCoordinator;
import com.yulong.chatagent.ratelimit.capacity.AgentRunCapacityLimiter;
import com.yulong.chatagent.ratelimit.capacity.CapacityGateResult;
import com.yulong.chatagent.ratelimit.capacity.Permit;
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
    private final AgentRunCapacityLimiter capacityLimiter;
    private final SessionRunCoordinator sessionRunCoordinator;

    /**
     * 收到本地事件后执行 Agent 任务。
     *
     * @param event 已准备好的用户 turn 事件
     */
    @Async
    @EventListener
    public void handle(ChatEvent event) {
        // 本地路径（MQ 禁用）只用 LOCAL_CAP：JVM Semaphore，不调 Redis，
        // 与生产 Redis 降级时的 LOCAL_CAP 行为一致。
        CapacityGateResult capacityResult = capacityLimiter.tryAcquireLocalCapOnly();
        if (capacityResult instanceof CapacityGateResult.WaitInQueue) {
            log.warn("Local capacity cap exhausted on MQ-disabled path, rejecting chat event: sessionId={}, turnId={}",
                    event.getSessionId(), event.getTurnId());
            IllegalStateException ex = new IllegalStateException("Local agent capacity exhausted");
            chatEventProcessor.publishFailure(event, ex);
            return;
        }
        CapacityGateResult.Proceed proceed = (CapacityGateResult.Proceed) capacityResult;
        try (Permit ignored = proceed.permit()) {
            // Phase 6: serialize same-session turns on the local path.
            // The MQ path already has Redis-based session-exec-lock.
            boolean lockAcquired = sessionRunCoordinator.acquire(event.getSessionId());
            if (!lockAcquired) {
                log.warn("Session run lock not acquired, rejecting: sessionId={}, turnId={}",
                        event.getSessionId(), event.getTurnId());
                chatEventProcessor.publishFailure(event,
                        new IllegalStateException("Session is busy, please wait"));
                return;
            }
            try {
                chatEventProcessor.process(event);
            } catch (Exception ex) {
                log.error("Failed to process chat event: agentId={}, sessionId={}, userMessageId={}",
                        event.getAgentId(), event.getSessionId(), event.getChatMessageId(), ex);
                chatEventProcessor.publishFailure(event, ex);
            } finally {
                sessionRunCoordinator.release(event.getSessionId());
            }
        }
    }
}
