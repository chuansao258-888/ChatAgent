package com.yulong.chatagent.conversation.event;

/**
 * ChatEvent 派发接口。
 * <p>
 * 同一个 prepared turn 可以被派发到本地 Spring 事件路径，也可以写入 MQ outbox；
 * 上层编排不需要关心当前部署使用哪种执行通道。
 */
public interface ChatEventDispatcher {

    void dispatch(ChatEvent event);
}
