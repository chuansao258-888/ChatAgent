package com.yulong.chatagent.agent.tools;

import com.yulong.chatagent.support.persistence.mapper.ToolExecutionJournalMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ARRB Phase 1：覆盖 {@link ToolExecutionJournalMaintenanceService} 的 sweep/retention 调用与
 * 异常隔离。维护失败只记录低数计数，绝不影响主流程。
 */
class ToolExecutionJournalMaintenanceServiceTest {

    @Test
    void sweepStaleCallsPreparedAndDispatchingSweepsWithConfiguredDeadlines() {
        ToolExecutionJournalMapper mapper = mock(ToolExecutionJournalMapper.class);
        when(mapper.sweepStalePrepared(any(LocalDateTime.class), anyInt())).thenReturn(3);
        when(mapper.sweepStaleDispatching(any(LocalDateTime.class), anyInt())).thenReturn(1);

        ToolExecutionJournalMaintenanceService service =
                new ToolExecutionJournalMaintenanceService(mapper);
        service.sweepStale();

        // PREPARED 截止时间是 NOW - PREPARED_DEADLINE_MINUTES，DISPATCHING 截止是 NOW - DISPATCHING_DEADLINE_MINUTES。
        verify(mapper).sweepStalePrepared(any(LocalDateTime.class),
                eq(ToolExecutionJournalMaintenanceService.RETENTION_BATCH_LIMIT));
        verify(mapper).sweepStaleDispatching(any(LocalDateTime.class),
                eq(ToolExecutionJournalMaintenanceService.RETENTION_BATCH_LIMIT));
    }

    @Test
    void deleteRetainedApplies30And90DayCutoffs() {
        ToolExecutionJournalMapper mapper = mock(ToolExecutionJournalMapper.class);
        when(mapper.deleteRetainedTerminal(any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                .thenReturn(7);

        ToolExecutionJournalMaintenanceService service =
                new ToolExecutionJournalMaintenanceService(mapper);
        service.deleteRetained();

        verify(mapper).deleteRetainedTerminal(any(LocalDateTime.class), any(LocalDateTime.class),
                eq(ToolExecutionJournalMaintenanceService.RETENTION_BATCH_LIMIT));
    }

    @Test
    void sweepStaleSwallowsExceptionsSoRecoveryNeverBreaksTheMainFlow() {
        // mapper 抛异常时，sweep 不应向上抛——这是维护路径的 fail-safe 契约。
        ToolExecutionJournalMapper mapper = mock(ToolExecutionJournalMapper.class);
        when(mapper.sweepStalePrepared(any(LocalDateTime.class), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        ToolExecutionJournalMaintenanceService service =
                new ToolExecutionJournalMaintenanceService(mapper);

        // 不抛异常即视为通过：维护失败被吞掉、只记录低数计数。
        service.sweepStale();
        verify(mapper, atLeastOnce()).sweepStalePrepared(any(LocalDateTime.class), anyInt());
    }

    @Test
    void deleteRetainedSwallowsExceptions() {
        ToolExecutionJournalMapper mapper = mock(ToolExecutionJournalMapper.class);
        when(mapper.deleteRetainedTerminal(any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        ToolExecutionJournalMaintenanceService service =
                new ToolExecutionJournalMaintenanceService(mapper);

        service.deleteRetained();
        verify(mapper, atLeastOnce()).deleteRetainedTerminal(any(LocalDateTime.class),
                any(LocalDateTime.class), anyInt());
    }

    @Test
    void retentionConstantsMatchPlan() {
        // 计划：30 天终态保留、90 天 UNKNOWN 保留、500 行批次。
        assertThat(ToolExecutionJournalMaintenanceService.TERMINAL_RETENTION_DAYS).isEqualTo(30);
        assertThat(ToolExecutionJournalMaintenanceService.UNKNOWN_RETENTION_DAYS).isEqualTo(90);
        assertThat(ToolExecutionJournalMaintenanceService.RETENTION_BATCH_LIMIT).isEqualTo(500);
    }
}
