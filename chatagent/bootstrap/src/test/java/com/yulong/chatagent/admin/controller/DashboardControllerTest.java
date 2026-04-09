package com.yulong.chatagent.admin.controller;

import com.yulong.chatagent.access.RequireRoleInterceptor;
import com.yulong.chatagent.admin.application.DashboardFacadeService;
import com.yulong.chatagent.admin.model.vo.DashboardOverviewVO;
import com.yulong.chatagent.admin.model.vo.DashboardPerformanceVO;
import com.yulong.chatagent.admin.model.vo.DashboardTrendsVO;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardControllerTest {

    private DashboardFacadeService dashboardFacadeService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dashboardFacadeService = mock(DashboardFacadeService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DashboardController(dashboardFacadeService))
                .addInterceptors(new RequireRoleInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldReturnOverviewForAdminUserAndUseDefaultWindow() throws Exception {
        UserContext.set(adminUser());
        when(dashboardFacadeService.getOverview("24h")).thenReturn(
                DashboardOverviewVO.builder()
                        .window("24h")
                        .compareWindow("prev_24h")
                        .updatedAt(1_712_345_678_000L)
                        .kpis(DashboardOverviewVO.DashboardOverviewGroupVO.builder()
                                .totalUsers(kpi(120L, 20L, 20.0))
                                .activeUsers(kpi(25L, 5L, 25.0))
                                .totalSessions(kpi(300L, 20L, null))
                                .sessions24h(kpi(40L, 8L, 25.0))
                                .totalMessages(kpi(900L, 60L, null))
                                .messages24h(kpi(80L, 30L, 60.0))
                                .build())
                        .build()
        );

        mockMvc.perform(get("/api/admin/dashboard/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.window").value("24h"))
                .andExpect(jsonPath("$.data.compareWindow").value("prev_24h"))
                .andExpect(jsonPath("$.data.kpis.totalUsers.value").value(120))
                .andExpect(jsonPath("$.data.kpis.sessions24h.deltaPct").value(25.0));

        verify(dashboardFacadeService).getOverview("24h");
    }

    @Test
    void shouldBindPerformanceWindowQueryParameter() throws Exception {
        UserContext.set(adminUser());
        when(dashboardFacadeService.getPerformance("30d")).thenReturn(
                DashboardPerformanceVO.builder()
                        .window("30d")
                        .avgLatencyMs(1680L)
                        .p95LatencyMs(3200L)
                        .successRate(92.4)
                        .errorRate(7.6)
                        .noDocRate(15.0)
                        .slowRate(8.2)
                        .build()
        );

        mockMvc.perform(get("/api/admin/dashboard/performance").param("window", "30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.window").value("30d"))
                .andExpect(jsonPath("$.data.avgLatencyMs").value(1680))
                .andExpect(jsonPath("$.data.successRate").value(92.4));

        verify(dashboardFacadeService).getPerformance("30d");
    }

    @Test
    void shouldBindTrendRequestParametersForAdminUser() throws Exception {
        UserContext.set(adminUser());
        when(dashboardFacadeService.getTrends("quality", "7d", "day")).thenReturn(
                DashboardTrendsVO.builder()
                        .metric("quality")
                        .window("7d")
                        .granularity("day")
                        .series(List.of(
                                DashboardTrendsVO.DashboardSeriesVO.builder()
                                        .name("Error rate")
                                        .data(List.of(
                                                DashboardTrendsVO.DashboardPointVO.builder()
                                                        .ts(1_712_300_000_000L)
                                                        .value(12.5)
                                                        .build()
                                        ))
                                        .build()
                        ))
                        .build()
        );

        mockMvc.perform(get("/api/admin/dashboard/trends")
                        .param("metric", "quality")
                        .param("window", "7d")
                        .param("granularity", "day"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metric").value("quality"))
                .andExpect(jsonPath("$.data.granularity").value("day"))
                .andExpect(jsonPath("$.data.series[0].name").value("Error rate"))
                .andExpect(jsonPath("$.data.series[0].data[0].value").value(12.5));

        ArgumentCaptor<String> metricCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> windowCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> granularityCaptor = ArgumentCaptor.forClass(String.class);
        verify(dashboardFacadeService).getTrends(
                metricCaptor.capture(),
                windowCaptor.capture(),
                granularityCaptor.capture()
        );
        assertThat(metricCaptor.getValue()).isEqualTo("quality");
        assertThat(windowCaptor.getValue()).isEqualTo("7d");
        assertThat(granularityCaptor.getValue()).isEqualTo("day");
    }

    @Test
    void shouldRejectNonAdminUserBeforeCallingFacade() throws Exception {
        UserContext.set(LoginUser.builder()
                .userId("user-1")
                .username("Normal User")
                .role("user")
                .build());

        mockMvc.perform(get("/api/admin/dashboard/overview"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("Required role: ADMIN"));

        verifyNoInteractions(dashboardFacadeService);
    }

    private static DashboardOverviewVO.DashboardOverviewKpiVO kpi(Long value, Long delta, Double deltaPct) {
        return DashboardOverviewVO.DashboardOverviewKpiVO.builder()
                .value(value)
                .delta(delta)
                .deltaPct(deltaPct)
                .build();
    }

    private static LoginUser adminUser() {
        return LoginUser.builder()
                .userId("admin-1")
                .username("Admin")
                .role("admin")
                .build();
    }
}
