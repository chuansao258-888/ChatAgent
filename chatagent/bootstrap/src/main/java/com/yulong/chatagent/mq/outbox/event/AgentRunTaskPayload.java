package com.yulong.chatagent.mq.outbox.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;
import com.yulong.chatagent.conversation.event.ChatEvent;
import com.yulong.chatagent.intent.application.IntentResolution;

/**
 * {@code agent.run} MQ 任务的序列化载荷。
 * <p>
 * 它是 ChatEvent 的消息队列形态，额外带 forceRollback，用于 DLQ replay 或人工重放时
 * 强制清理本 turn 已有输出。
 */
public record AgentRunTaskPayload(
        String agentId,
        String sessionId,
        String turnId,
        Long turnSeq,
        String chatMessageId,
        String userInput,
        int recentHistorySize,
        IntentResolution intentResolution,
        String rewrittenInput,
        String userId,
        AgentExecutionMode executionMode,
        boolean forceRollback,
        TurnExecutionContract executionContract
) {

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public AgentRunTaskPayload(
            @JsonProperty("agentId") String agentId,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("turnId") String turnId,
            @JsonProperty("turnSeq") Long turnSeq,
            @JsonProperty("chatMessageId") String chatMessageId,
            @JsonProperty("userInput") String userInput,
            @JsonProperty("recentHistorySize") int recentHistorySize,
            @JsonProperty("intentResolution") IntentResolution intentResolution,
            @JsonProperty("rewrittenInput") String rewrittenInput,
            @JsonProperty(value = "userId", defaultValue = "") String userId,
            @JsonProperty("executionMode") AgentExecutionMode executionMode,
            @JsonProperty(value = "forceRollback", defaultValue = "false") boolean forceRollback,
            @JsonProperty("executionContract") TurnExecutionContract executionContract
    ) {
        // JsonCreator 构造器保证老消息缺少 userId/executionMode/forceRollback/executionContract 字段时仍能兼容反序列化。
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.turnSeq = turnSeq;
        this.chatMessageId = chatMessageId;
        this.userInput = userInput;
        this.recentHistorySize = recentHistorySize;
        this.intentResolution = intentResolution;
        this.rewrittenInput = rewrittenInput;
        this.userId = userId == null ? "" : userId;
        this.executionMode = executionMode == null ? AgentExecutionMode.REACT : executionMode;
        this.forceRollback = forceRollback;
        this.executionContract = executionContract;
    }

    public AgentRunTaskPayload(String agentId,
                               String sessionId,
                               String turnId,
                               Long turnSeq,
                               String chatMessageId,
                               String userInput,
                               int recentHistorySize,
                               IntentResolution intentResolution,
                               String rewrittenInput,
                               String userId,
                               boolean forceRollback) {
        this(agentId, sessionId, turnId, turnSeq, chatMessageId, userInput, recentHistorySize,
                intentResolution, rewrittenInput, userId, AgentExecutionMode.REACT, forceRollback, null);
    }

    public static AgentRunTaskPayload fromChatEvent(ChatEvent event) {
        // 正常派发时不强制 rollback；只有重试或人工 replay 会打开这个标记。
        return new AgentRunTaskPayload(
                event.getAgentId(),
                event.getSessionId(),
                event.getTurnId(),
                event.getTurnSeq(),
                event.getChatMessageId(),
                event.getUserInput(),
                event.getRecentHistorySize(),
                event.getIntentResolution(),
                event.getRewrittenInput(),
                event.getUserId(),
                event.getExecutionMode(),
                false,
                event.getExecutionContract()
        );
    }

    public AgentRunTaskPayload withForceRollback(boolean forceRollback) {
        // record 不可变，通过复制创建带新 forceRollback 值的载荷。
        return new AgentRunTaskPayload(
                agentId,
                sessionId,
                turnId,
                turnSeq,
                chatMessageId,
                userInput,
                recentHistorySize,
                intentResolution,
                rewrittenInput,
                userId,
                executionMode,
                forceRollback,
                executionContract
        );
    }

    public ChatEvent toChatEvent() {
        // consumer 侧恢复成 ChatEvent，后续与本地异步路径复用同一个 ChatEventProcessor。
        return new ChatEvent(
                agentId,
                sessionId,
                turnId,
                turnSeq,
                chatMessageId,
                userInput,
                recentHistorySize,
                intentResolution,
                rewrittenInput,
                userId,
                executionMode,
                executionContract
        );
    }
}
