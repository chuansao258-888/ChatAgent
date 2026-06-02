package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.intent.application.IntentResolution;
import lombok.Data;

/**
 * 一次已准备好的用户 turn 事件。
 * <p>
 * 它是同步入口线程与异步执行线程之间的桥接对象，也是本地异步与 MQ 路径共用的“最小运行快照”。
 * 这里刻意只放：
 * <ul>
 *     <li>agent/session/turn 等稳定标识；</li>
 *     <li>用户输入和最近历史规模；</li>
 *     <li>prepare 阶段得到的意图结果与 rewrite 结果。</li>
 * </ul>
 * 它不会携带完整历史消息、完整 memory 或 tool callbacks，
 * 因为这些内容应该由异步执行侧按最新数据重新加载。
 */
@Data
public class ChatEvent {

    private String agentId;
    private String sessionId;
    private String turnId;
    private Long turnSeq;
    private String chatMessageId;
    private String userInput;
    private int recentHistorySize;
    private IntentResolution intentResolution;
    private String rewrittenInput;
    private String userId;
    private AgentExecutionMode executionMode;

    public ChatEvent(String agentId,
                     String sessionId,
                     String turnId,
                     String chatMessageId,
                     String userInput,
                     int recentHistorySize,
                     IntentResolution intentResolution,
                     String rewrittenInput) {
        this(agentId, sessionId, turnId, chatMessageId, userInput, recentHistorySize, intentResolution, rewrittenInput, null);
    }

    public ChatEvent(String agentId,
                     String sessionId,
                     String turnId,
                     String chatMessageId,
                     String userInput,
                     int recentHistorySize,
                     IntentResolution intentResolution,
                     String rewrittenInput,
                     String userId) {
        this(agentId, sessionId, turnId, null, chatMessageId, userInput, recentHistorySize, intentResolution, rewrittenInput, userId, AgentExecutionMode.REACT);
    }

    public ChatEvent(String agentId,
                     String sessionId,
                     String turnId,
                     Long turnSeq,
                     String chatMessageId,
                     String userInput,
                     int recentHistorySize,
                     IntentResolution intentResolution,
                     String rewrittenInput,
                     String userId) {
        this(agentId, sessionId, turnId, turnSeq, chatMessageId, userInput, recentHistorySize, intentResolution, rewrittenInput, userId, AgentExecutionMode.REACT);
    }

    public ChatEvent(String agentId,
                     String sessionId,
                     String turnId,
                     Long turnSeq,
                     String chatMessageId,
                     String userInput,
                     int recentHistorySize,
                     IntentResolution intentResolution,
                     String rewrittenInput,
                     String userId,
                     AgentExecutionMode executionMode) {
        // 这里不做额外业务逻辑，只负责把 turn 执行所需的最小字段稳定封装起来。
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.turnSeq = turnSeq;
        this.chatMessageId = chatMessageId;
        this.userInput = userInput;
        this.recentHistorySize = recentHistorySize;
        this.intentResolution = intentResolution;
        this.rewrittenInput = rewrittenInput;
        this.userId = userId;
        this.executionMode = executionMode == null ? AgentExecutionMode.REACT : executionMode;
    }
}
