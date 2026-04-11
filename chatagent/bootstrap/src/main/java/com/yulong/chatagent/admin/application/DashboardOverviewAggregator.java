package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.vo.DashboardOverviewVO;
import com.yulong.chatagent.support.persistence.mapper.DashboardMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Component
class DashboardOverviewAggregator {

    private final DashboardMapper dashboardMapper;

    DashboardOverviewAggregator(DashboardMapper dashboardMapper) {
        this.dashboardMapper = dashboardMapper;
    }

    DashboardOverviewVO aggregate(LocalDateTime currentStart, LocalDateTime currentEnd,
                                  LocalDateTime previousStart, LocalDateTime previousEnd,
                                  String window, String compareWindow, long updatedAt) {
        long totalUsers = safeLong(dashboardMapper.countUsersBefore(currentEnd));
        long totalUsersPrevious = safeLong(dashboardMapper.countUsersBefore(currentStart));
        long activeUsers = safeLong(dashboardMapper.countActiveUsersBetween(currentStart, currentEnd));
        long activeUsersPrevious = safeLong(dashboardMapper.countActiveUsersBetween(previousStart, previousEnd));
        long totalSessions = safeLong(dashboardMapper.countSessionsBefore(currentEnd));
        long totalSessionsPrevious = safeLong(dashboardMapper.countSessionsBefore(currentStart));
        long sessionsInWindow = safeLong(dashboardMapper.countSessionsBetween(currentStart, currentEnd));
        long sessionsInPreviousWindow = safeLong(dashboardMapper.countSessionsBetween(previousStart, previousEnd));
        long totalMessages = safeLong(dashboardMapper.countMessagesBefore(currentEnd));
        long totalMessagesPrevious = safeLong(dashboardMapper.countMessagesBefore(currentStart));
        long messagesInWindow = safeLong(dashboardMapper.countMessagesBetween(currentStart, currentEnd));
        long messagesInPreviousWindow = safeLong(dashboardMapper.countMessagesBetween(previousStart, previousEnd));

        return DashboardOverviewVO.builder()
                .window(window)
                .compareWindow(compareWindow)
                .updatedAt(updatedAt)
                .kpis(DashboardOverviewVO.DashboardOverviewGroupVO.builder()
                        .totalUsers(buildKpi(totalUsers, totalUsers - totalUsersPrevious, percentageDelta(totalUsers, totalUsersPrevious)))
                        .activeUsers(buildKpi(activeUsers, activeUsers - activeUsersPrevious, percentageDelta(activeUsers, activeUsersPrevious)))
                        .totalSessions(buildKpi(totalSessions, totalSessions - totalSessionsPrevious, null))
                        .sessions24h(buildKpi(
                                sessionsInWindow,
                                sessionsInWindow - sessionsInPreviousWindow,
                                percentageDelta(sessionsInWindow, sessionsInPreviousWindow)
                        ))
                        .totalMessages(buildKpi(totalMessages, totalMessages - totalMessagesPrevious, null))
                        .messages24h(buildKpi(
                                messagesInWindow,
                                messagesInWindow - messagesInPreviousWindow,
                                percentageDelta(messagesInWindow, messagesInPreviousWindow)
                        ))
                        .build())
                .build();
    }

    private DashboardOverviewVO.DashboardOverviewKpiVO buildKpi(long value, long delta, Double deltaPct) {
        return DashboardOverviewVO.DashboardOverviewKpiVO.builder()
                .value(value)
                .delta(delta)
                .deltaPct(deltaPct)
                .build();
    }

    private Double percentageDelta(long current, long previous) {
        if (previous <= 0) {
            return null;
        }
        return roundToOneDecimal((current - previous) * 100.0 / previous);
    }

    private Double roundToOneDecimal(double value) {
        return BigDecimal.valueOf(value)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private long safeLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
