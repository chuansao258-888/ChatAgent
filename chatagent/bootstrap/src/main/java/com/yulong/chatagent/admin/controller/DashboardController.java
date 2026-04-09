package com.yulong.chatagent.admin.controller;

import com.yulong.chatagent.access.RequireRole;
import com.yulong.chatagent.access.UserRole;
import com.yulong.chatagent.admin.application.DashboardFacadeService;
import com.yulong.chatagent.admin.model.vo.DashboardMcpAlertsVO;
import com.yulong.chatagent.admin.model.vo.DashboardOverviewVO;
import com.yulong.chatagent.admin.model.vo.DashboardPerformanceVO;
import com.yulong.chatagent.admin.model.vo.DashboardTrendsVO;
import com.yulong.chatagent.model.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator dashboard endpoints.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
@RequireRole(UserRole.ADMIN)
public class DashboardController {

    private final DashboardFacadeService dashboardFacadeService;

    @GetMapping("/overview")
    public ApiResponse<DashboardOverviewVO> getOverview(
            @RequestParam(defaultValue = "24h") String window) {
        return ApiResponse.success(dashboardFacadeService.getOverview(window));
    }

    @GetMapping("/performance")
    public ApiResponse<DashboardPerformanceVO> getPerformance(
            @RequestParam(defaultValue = "24h") String window) {
        return ApiResponse.success(dashboardFacadeService.getPerformance(window));
    }

    @GetMapping("/trends")
    public ApiResponse<DashboardTrendsVO> getTrends(
            @RequestParam String metric,
            @RequestParam(defaultValue = "7d") String window,
            @RequestParam(required = false) String granularity) {
        return ApiResponse.success(dashboardFacadeService.getTrends(metric, window, granularity));
    }

    @GetMapping("/mcp-alerts")
    public ApiResponse<DashboardMcpAlertsVO> getMcpAlerts(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(dashboardFacadeService.getMcpAlerts(limit));
    }
}
