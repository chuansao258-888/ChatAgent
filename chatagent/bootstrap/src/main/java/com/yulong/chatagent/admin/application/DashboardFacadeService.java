package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.vo.DashboardOverviewVO;
import com.yulong.chatagent.admin.model.vo.DashboardMcpAlertsVO;
import com.yulong.chatagent.admin.model.vo.DashboardPerformanceVO;
import com.yulong.chatagent.admin.model.vo.DashboardTrendsVO;

/**
 * Admin-facing dashboard read API.
 */
public interface DashboardFacadeService {

    DashboardOverviewVO getOverview(String window);

    DashboardPerformanceVO getPerformance(String window);

    DashboardTrendsVO getTrends(String metric, String window, String granularity);

    DashboardMcpAlertsVO getMcpAlerts(int limit);
}
