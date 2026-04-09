package com.yulong.chatagent.admin.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Dashboard KPI overview payload.
 */
@Data
@Builder
public class DashboardOverviewVO {

    private String window;

    private String compareWindow;

    private Long updatedAt;

    private DashboardOverviewGroupVO kpis;

    @Data
    @Builder
    public static class DashboardOverviewGroupVO {
        private DashboardOverviewKpiVO totalUsers;
        private DashboardOverviewKpiVO activeUsers;
        private DashboardOverviewKpiVO totalSessions;
        private DashboardOverviewKpiVO sessions24h;
        private DashboardOverviewKpiVO totalMessages;
        private DashboardOverviewKpiVO messages24h;
    }

    @Data
    @Builder
    public static class DashboardOverviewKpiVO {
        private Long value;
        private Long delta;
        private Double deltaPct;
    }
}
