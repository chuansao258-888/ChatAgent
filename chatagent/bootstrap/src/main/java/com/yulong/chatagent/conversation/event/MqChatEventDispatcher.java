package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.outbox.OutboxEventPublisher;
import com.yulong.chatagent.mq.outbox.event.AgentRunTaskPayload;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.trace.TraceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MQ 派发路径：把 {@code agent.run} 任务写入事务 outbox。
 * <p>
 * outbox 先随业务事务落库，再由轮询发布器投递到 RabbitMQ，避免“用户消息已保存但任务丢失”。
 */
@Component
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
public class MqChatEventDispatcher implements ChatEventDispatcher {

    private final OutboxEventPublisher outboxEventPublisher;
    private final ChatAgentMqProperties properties;

    public MqChatEventDispatcher(OutboxEventPublisher outboxEventPublisher, ChatAgentMqProperties properties) {
        this.outboxEventPublisher = outboxEventPublisher;
        this.properties = properties;
    }

    @Override
    public void dispatch(ChatEvent event) {
        // identity 是 MQ 幂等和追踪信息。
        // MqMessageIdentity.initial() 内部会生成随机 eventId，用来标识这一次投递事件；
        // 这里使用 sessionId + turnId 作为 idempotencyKey，才是同一轮重试/replay 复用的稳定业务键。
        // 比单独使用 turnId 更防御：即使外部调用方误复用了 turnId，也不会跨 session 相互去重。
        // 后续 outbox 去重和 Redis 消费锁都会围绕 taskType + idempotencyKey 判断是否重复。
        MqMessageIdentity identity = MqMessageIdentity.initial(
                "agent.run",
                idempotencyKey(event),
                TraceContext.getTraceId(),
                event.getSessionId(),
                properties.getExchanges().getChatDirect(),
                properties.getRoutingKeys().getAgentRun()
        );
        // payload 是 ChatEvent 的可序列化快照；
        // 进入 MQ 之后会被还原成 ChatEvent，再统一进入 ChatEventProcessor。
        outboxEventPublisher.publish(
                "agent.run",
                properties.getExchanges().getChatDirect(),
                properties.getRoutingKeys().getAgentRun(),
                AgentRunTaskPayload.fromChatEvent(event),
                identity
        );
    }

    private String idempotencyKey(ChatEvent event) {
        return event.getSessionId() + ":" + event.getTurnId();
    }
}
