package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.ToolExecutionJournal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ARRB Phase 1：{@code t_tool_execution_journal} 的 MyBatis mapper。
 * <p>
 * 状态机依赖 compare-and-set：所有状态推进都通过 {@code WHERE ... AND state = expected}
 * 返回受影响行数，0 表示冲突。Terminal CAS 与配对响应消息写入共享一个上层事务
 * （见 {@code ToolExecutionLedgerPort} 实现），journal 本身只提供原子 SQL。
 */
@Mapper
public interface ToolExecutionJournalMapper {

    int insert(ToolExecutionJournal entity);

    ToolExecutionJournal selectByExecutionKey(@Param("executionKey") String executionKey);

    /**
     * Compare-and-set from {@code expectedState} to {@code DISPATCHING}：
     * 用于把一个已校验+授权的派发从 PREPARED 推进到 DISPATCHING（外部回调调用前）。
     * 返回受影响行数，0 表示状态已被其他进程推进或键不存在。
     */
    int casToDispatching(@Param("executionKey") String executionKey,
                         @Param("expectedState") String expectedState,
                         @Param("attempt") int attempt,
                         @Param("dispatchedAt") LocalDateTime dispatchedAt);

    int casToRetryPrepared(@Param("executionKey") String executionKey,
                           @Param("expectedAttempt") int expectedAttempt,
                           @Param("nextAttempt") int nextAttempt,
                           @Param("safeErrorCode") String safeErrorCode);

    /**
     * Compare-and-set to a terminal state，并记录配对响应引用/哈希与 safe error code。
     * 返回受影响行数。
     */
    int casToTerminal(@Param("executionKey") String executionKey,
                      @Param("expectedState") String expectedState,
                      @Param("newState") String newState,
                      @Param("expectedAttempt") int expectedAttempt,
                      @Param("responseMessageId") String responseMessageId,
                      @Param("responseHash") String responseHash,
                      @Param("safeErrorCode") String safeErrorCode);

    default int casToTerminal(String executionKey,
                              String expectedState,
                              String newState,
                              String responseMessageId,
                              String responseHash,
                              String safeErrorCode) {
        return casToTerminal(executionKey, expectedState, newState, 1,
                responseMessageId, responseHash, safeErrorCode);
    }

    /**
     * 维护用：把超过截止时间仍滞留 PREPARED 的行推进为 BLOCKED。
     */
    int sweepStalePrepared(@Param("cutoff") LocalDateTime cutoff,
                           @Param("batchLimit") int batchLimit);

    /**
     * 维护用：把超过 call deadline 仍滞留 DISPATCHING 的行推进为 OUTCOME_UNKNOWN。
     */
    int sweepStaleDispatching(@Param("cutoff") LocalDateTime cutoff,
                              @Param("batchLimit") int batchLimit);

    /**
     * 维护用：有界删除超过保留期的终态行。只删除 SUCCEEDED/FAILED_KNOWN/BLOCKED（>30 天）
     * 或已过期的 OUTCOME_UNKNOWN（>90 天），绝不删除活跃行或未过期审批。
     */
    int deleteRetainedTerminal(@Param("retainedCutoff") LocalDateTime retainedCutoff,
                               @Param("unknownCutoff") LocalDateTime unknownCutoff,
                               @Param("batchLimit") int batchLimit);

    /**
     * 测试/排查用：按 session 查询全部 journal 行。
     */
    List<ToolExecutionJournal> selectBySessionId(@Param("sessionId") String sessionId);
}
