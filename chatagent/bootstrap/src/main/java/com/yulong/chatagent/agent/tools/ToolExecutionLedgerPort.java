package com.yulong.chatagent.agent.tools;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 行为导向的工具执行 ledger 端口（ARRB Phase 1，plan ARRB-DEC-010 / ARRB-AC-009）。
 * <p>
 * 它是 coordinator 与底层 journal 持久化之间的唯一边界，对外只暴露三类操作：
 * <ul>
 *   <li>{@link #prepare}：为一个校验+授权后的派发写入 PREPARED 行（唯一 execution key）；</li>
 *   <li>{@link #tryDispatch}：compare-and-set 从期望状态推进到 DISPATCHING（外部回调调用前）；</li>
 *   <li>{@link #commitTerminal}：compare-and-set 推进到终态，并在同一事务里写入配对响应引用/哈希。</li>
 * </ul>
 * 实现隐藏 journal repository、transaction 与 conversation-message 持久化。
 * external callback 永远不在 {@link #commitTerminal} 的事务内；SSE 发布在事务提交后才发生。
 */
public interface ToolExecutionLedgerPort {

    /**
     * 为一次校验+授权后的派发写入 PREPARED journal 行。execution key 唯一：重复插入会因
     * unique 约束失败（调用方据此实现"已派发则不重复"）。
     *
     * @param entry sanitized journal 输入；executionKey/sessionId/toolName/argumentHash 必填
     * @return 已写入的 journal 行（含生成的 id）；若 execution key 已存在则返回 empty
     */
    Optional<JournalEntry> prepare(JournalEntry entry);

    /**
     * Compare-and-set：把一个 PREPARED 行推进到 DISPATCHING。返回是否成功推进。
     * 失败（0 受影响行）意味着状态已被其他进程推进或键不存在，coordinator 据此保守处理。
     */
    boolean tryDispatch(String executionKey, String expectedState, int attempt, LocalDateTime dispatchedAt);

    default boolean prepareRetry(String executionKey,
                                 int expectedAttempt,
                                 int nextAttempt,
                                 String safeErrorCode) {
        return false;
    }

    /**
     * Compare-and-set 到终态，并在同一事务里写入配对响应引用/哈希与 safe error code。
     * 返回是否成功推进。失败意味着状态已变化（例如已被 recovery 推进为 OUTCOME_UNKNOWN），
     * coordinator 不得据此声明成功。
     */
    boolean commitTerminal(String executionKey, String expectedState, TerminalUpdate update);

    /** Atomically persists one paired TOOL message and advances the journal terminal CAS. */
    default PersistedToolResponse commitTerminalResponse(String executionKey,
                                                         String expectedState,
                                                         TerminalUpdate update,
                                                         ToolResponseCommitRequest response) {
        throw new UnsupportedOperationException("Atomic tool response commit is not configured");
    }

    /**
     * 按 execution key 查询当前 journal 行，用于恢复：SUCCEEDED 行应直接加载已提交的配对响应。
     */
    Optional<JournalEntry> findByExecutionKey(String executionKey);

    default Optional<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse>
    loadCommittedResponse(String executionKey) {
        return Optional.empty();
    }

    /**
     * Sanitized journal 输入/读取 DTO。只承载规范化身份、状态、哈希，不承载原始参数/结果。
     */
    record JournalEntry(
            String id,
            String executionKey,
            String sessionId,
            String turnId,
            String approvalId,
            String assistantMessageId,
            String toolCallId,
            String toolName,
            String argumentHash,
            String effectClass,
            int attempt,
            String state,
            String safeErrorCode,
            String responseMessageId,
            String responseHash,
            LocalDateTime dispatchedAt,
            Long callDeadlineMs,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * 终态推进的输入：新的终态、配对响应消息引用/哈希、safe error code。
     */
    record TerminalUpdate(
            String newState,
            String responseMessageId,
            String responseHash,
            String safeErrorCode,
            int expectedAttempt) {

        public TerminalUpdate(String newState,
                              String responseMessageId,
                              String responseHash,
                              String safeErrorCode) {
            this(newState, responseMessageId, responseHash, safeErrorCode, 1);
        }
    }

    record ToolResponseCommitRequest(
            String sessionId,
            String turnId,
            org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse response,
            boolean internal,
            String deepThinkPhase,
            String planStepId) {
    }

    record PersistedToolResponse(
            String messageId,
            Long turnSeq,
            ToolResponseCommitRequest committed) {
    }
}
