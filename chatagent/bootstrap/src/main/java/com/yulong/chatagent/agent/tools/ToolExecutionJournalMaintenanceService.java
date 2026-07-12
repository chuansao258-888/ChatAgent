package com.yulong.chatagent.agent.tools;

import com.yulong.chatagent.support.persistence.mapper.ToolExecutionJournalMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * ARRB Phase 1：bounded recovery/retention for {@code t_tool_execution_journal}
 * （plan ARRB-DEC-010, L404-414）。
 * <p>
 * coordinator 不负责过期回收；本服务负责把滞留状态安全推进并把终态行按保留期有界删除。
 * 所有周期与批次大小是 tested internal constants：
 * <ul>
 *   <li>PREPARED 超过 {@link #PREPARED_DEADLINE_MINUTES} 推进为 BLOCKED；</li>
 *   <li>DISPATCHING 超过 {@link #DISPATCHING_DEADLINE_MINUTES} 推进为 OUTCOME_UNKNOWN；</li>
 *   <li>SUCCEEDED/FAILED_KNOWN/BLOCKED 保留 {@link #TERMINAL_RETENTION_DAYS} 天；</li>
 *   <li>OUTCOME_UNKNOWN 保留 {@link #UNKNOWN_RETENTION_DAYS} 天；</li>
 *   <li>每轮最多删除 {@link #RETENTION_BATCH_LIMIT} 行。</li>
 * </ul>
 * 它从不删除未过期审批或活跃行；执行失败只记录低基数计数，不影响主流程。
 */
@Slf4j
@Service
public class ToolExecutionJournalMaintenanceService {

    static final int PREPARED_DEADLINE_MINUTES = 15;
    static final int DISPATCHING_DEADLINE_MINUTES = 30;
    static final int TERMINAL_RETENTION_DAYS = 30;
    static final int UNKNOWN_RETENTION_DAYS = 90;
    static final int RETENTION_BATCH_LIMIT = 500;

    private final ToolExecutionJournalMapper mapper;

    public ToolExecutionJournalMaintenanceService(ToolExecutionJournalMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 周期性有界回收：推进滞留状态。默认每 {@link #SWEEP_CRON} 运行一次。
     */
    @Scheduled(cron = ToolExecutionJournalMaintenanceService.SWEEP_CRON)
    public void sweepStale() {
        try {
            LocalDateTime preparedCutoff = LocalDateTime.now().minusMinutes(PREPARED_DEADLINE_MINUTES);
            int blocked = mapper.sweepStalePrepared(preparedCutoff, RETENTION_BATCH_LIMIT);

            LocalDateTime dispatchingCutoff = LocalDateTime.now().minusMinutes(DISPATCHING_DEADLINE_MINUTES);
            int unknown = mapper.sweepStaleDispatching(dispatchingCutoff, RETENTION_BATCH_LIMIT);

            if (blocked > 0 || unknown > 0) {
                log.info("Tool execution journal sweep: stalePreparedBlocked={}, staleDispatchingUnknown={}",
                        blocked, unknown);
            }
        } catch (Exception e) {
            // 回收失败只记录低基数计数，不影响主流程；下一轮会重试。
            log.warn("Tool execution journal sweep failed", e);
        }
    }

    /**
     * 周期性有界保留删除。默认每天运行一次。
     */
    @Scheduled(cron = ToolExecutionJournalMaintenanceService.RETENTION_CRON)
    public void deleteRetained() {
        try {
            LocalDateTime terminalCutoff = LocalDateTime.now().minusDays(TERMINAL_RETENTION_DAYS);
            LocalDateTime unknownCutoff = LocalDateTime.now().minusDays(UNKNOWN_RETENTION_DAYS);
            int deleted = mapper.deleteRetainedTerminal(terminalCutoff, unknownCutoff, RETENTION_BATCH_LIMIT);
            if (deleted > 0) {
                log.info("Tool execution journal retention: deletedTerminalRows={}", deleted);
            }
        } catch (Exception e) {
            log.warn("Tool execution journal retention delete failed", e);
        }
    }

    static final String SWEEP_CRON = "0 */5 * * * *";        // every 5 minutes
    static final String RETENTION_CRON = "0 30 3 * * *";      // daily 03:30
}
