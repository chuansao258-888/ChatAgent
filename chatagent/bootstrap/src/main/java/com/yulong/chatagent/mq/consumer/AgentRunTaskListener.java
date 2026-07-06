package com.yulong.chatagent.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.yulong.chatagent.conversation.event.ChatEvent;
import com.yulong.chatagent.conversation.event.ChatEventProcessor;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.exception.ClientException;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.lock.DistributedLockManager;
import com.yulong.chatagent.mq.lock.LockWatchdog;
import com.yulong.chatagent.mq.outbox.event.AgentRunTaskPayload;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.mq.support.RabbitMqMessagePublisher;
import com.yulong.chatagent.ratelimit.capacity.AgentRunCapacityLimiter;
import com.yulong.chatagent.ratelimit.capacity.CapacityGateResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * {@code agent.run} 的 MQ 消费者。
 * <p>
 * 它负责把 RabbitMQ 消息反序列化成 AgentRunTaskPayload，获取重试/锁等基础能力后，
 * 最终交给 ChatEventProcessor 运行 Agent。
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "chatagent.mq", name = {"enabled", "dispatchers.agent-run-enabled"}, havingValue = "true")
public class AgentRunTaskListener extends AbstractRetryingMqConsumer<AgentRunTaskPayload> {

    private final ObjectMapper objectMapper;
    private final ChatAgentMqProperties properties;
    private final ChatEventProcessor chatEventProcessor;
    private final ChatSessionRepository chatSessionRepository;
    private final AgentRunCapacityLimiter capacityLimiter;

    public AgentRunTaskListener(ObjectMapper objectMapper,
                                ChatAgentMqProperties properties,
                                RabbitMqMessagePublisher rabbitMqMessagePublisher,
                                DistributedLockManager distributedLockManager,
                                LockWatchdog lockWatchdog,
                                ChatEventProcessor chatEventProcessor,
                                ChatSessionRepository chatSessionRepository,
                                AgentRunCapacityLimiter capacityLimiter) {
        super(properties, rabbitMqMessagePublisher, distributedLockManager, lockWatchdog);
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.chatEventProcessor = chatEventProcessor;
        this.chatSessionRepository = chatSessionRepository;
        this.capacityLimiter = capacityLimiter;
    }

    @RabbitListener(
            queues = "${chatagent.mq.queues.chat-agent-dispatch:chat.agent.dispatch}",
            containerFactory = "agentRunListenerContainerFactory"
    )
    public void handle(Message message, Channel channel) throws IOException {
        // 公共消费、ACK/NACK、重试和锁逻辑都在 AbstractRetryingMqConsumer 中。
        consume(message, channel);
    }

    @Override
    protected String retryExchange() {
        return properties.getExchanges().getRetryDirect();
    }

    @Override
    protected String retryRoutingKey() {
        return properties.getRoutingKeys().getRetryAgent();
    }

    @Override
    protected int maxRetryCount() {
        return 3;
    }

    @Override
    protected boolean isRetryable(Exception exception) {
        // 参数错误和客户端错误通常重试也不会恢复，直接进入终态失败处理。
        return !(exception instanceof IllegalArgumentException || exception instanceof ClientException);
    }

    @Override
    protected AgentRunTaskPayload deserializePayload(Message message) throws Exception {
        return objectMapper.readValue(message.getBody(), AgentRunTaskPayload.class);
    }

    @Override
    protected TaskReadiness checkReadiness(AgentRunTaskPayload payload, MqMessageIdentity identity) {
        if (payload == null || payload.turnSeq() == null || payload.turnSeq() <= 0L) {
            // 兼容没有 turnSeq 的旧 payload：只依赖 session exec lock，不做顺序闸门。
            return TaskReadiness.proceed();
        }
        // lastCompletedTurnSeq 是“这个 session 已经完整跑完的最后一轮”。
        // 当前 turn 必须刚好等于 lastCompleted + 1，才允许执行。
        Long lastCompleted = chatSessionRepository.findLastCompletedTurnSeq(payload.sessionId());
        long completedSeq = lastCompleted == null ? 0L : lastCompleted;
        if (payload.turnSeq() <= completedSeq) {
            // 小于等于 lastCompleted 说明这条消息已经处理过，重复投递直接跳过。
            return TaskReadiness.skip("turn sequence already completed");
        }
        if (payload.turnSeq() > completedSeq + 1) {
            // 大于 lastCompleted + 1 说明前面的 turn 还没完成，延迟重投以保护会话内顺序。
            return TaskReadiness.waitRequired("waiting for previous turn sequence");
        }
        return TaskReadiness.proceed();
    }

    @Override
    protected CapacityGateResult acquireCapacity(AgentRunTaskPayload payload, MqMessageIdentity identity) {
        // 在 readiness PROCEED 后、processTask 前获取全局执行许可。
        // owner/eventId/turnId 组合成唯一的 permit member，便于排查哪台实例在持许可。
        AgentRunCapacityLimiter.PermitContext context = AgentRunCapacityLimiter.PermitContext.forTask(
                consumerName(), identity.eventId(), payload.toChatEvent().getTurnId()
        );
        return capacityLimiter.tryAcquire(context);
    }

    @Override
    protected void processTask(AgentRunTaskPayload payload, MqMessageIdentity identity) {
        ChatEvent event = payload.toChatEvent();

        // 重试或人工 DLQ replay 前先清理本 turn 的旧输出，避免用户看到重复 assistant/tool 消息。
        if (identity.retryCount() > 0 || payload.forceRollback()) {
            chatEventProcessor.rollbackTurn(event.getSessionId(), event.getTurnId());
        }

        chatEventProcessor.process(event);
        log.info("Agent run task processed: eventId={}, turnId={}, sessionId={}",
                identity.eventId(), event.getTurnId(), event.getSessionId());
    }

    @Override
    protected void onRetriesExhausted(AgentRunTaskPayload payload, MqMessageIdentity identity, Exception exception) {
        publishFailureIfPossible(payload, identity, exception);
    }

    @Override
    protected void onTerminalFailure(AgentRunTaskPayload payload, MqMessageIdentity identity, Exception exception) {
        publishFailureIfPossible(payload, identity, exception);
    }

    private void publishFailureIfPossible(AgentRunTaskPayload payload,
                                          MqMessageIdentity identity,
                                          Exception exception) {
        if (payload == null) {
            // payload 都无法解析时，没有 session/turn 可用于补发用户可见消息，只能记录日志。
            log.warn("Skipping fallback assistant message because agent.run payload could not be deserialized: eventId={}",
                    identity.eventId());
            return;
        }
        // 重试耗尽后仍要通知前端结束 loading，并给用户一条明确失败消息。
        chatEventProcessor.publishFailure(payload.toChatEvent(), exception);
    }
}
