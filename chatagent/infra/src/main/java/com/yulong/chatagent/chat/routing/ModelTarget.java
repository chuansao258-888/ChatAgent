package com.yulong.chatagent.chat.routing;

import org.springframework.ai.chat.client.ChatClient;

/**
 * 路由层最终可调用的模型目标。
 *
 * <p>CandidateConfig 只来自配置，不能直接调用模型；ModelTarget 在配置基础上绑定了
 * ChatClient，因此 RoutingLLMService 可以直接对 target.chatClient() 发起请求。</p>
 */
public record ModelTarget(
        /** 路由内部使用的模型 id，一般对应 chat.routing.candidates[].id。 */
        String id,
        /** YAML 配置和运行时 override 合并后的候选模型配置。 */
        ChatRoutingProperties.CandidateConfig candidate,
        /** 实际发起 Spring AI ChatClient 调用的客户端。 */
        ChatClient chatClient
) {}
