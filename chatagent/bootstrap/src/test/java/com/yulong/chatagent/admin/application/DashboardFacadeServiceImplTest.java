package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.vo.DashboardOverviewVO;
import com.yulong.chatagent.admin.model.vo.DashboardPerformanceVO;
import com.yulong.chatagent.admin.model.vo.DashboardTrendsVO;
import com.yulong.chatagent.support.persistence.mapper.DashboardMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardFacadeServiceImplTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private DashboardMapper dashboardMapper;

    @Mock
    private DashboardMcpMetricsComposer mcpMetricsComposer;

    private DashboardFacadeServiceImpl facadeService;

    @BeforeEach
    void setUp() {
        facadeService = new DashboardFacadeServiceImpl(adminAccessService, dashboardMapper, mcpMetricsComposer);
    }

    @Test
    void shouldAssembleOverviewWithCurrentAndPreviousWindowDeltas() {
        when(dashboardMapper.countUsersBefore(any())).thenReturn(120L, 100L);
        when(dashboardMapper.countActiveUsersBetween(any(), any())).thenReturn(25L, 20L);
        when(dashboardMapper.countSessionsBefore(any())).thenReturn(300L, 280L);
        when(dashboardMapper.countSessionsBetween(any(), any())).thenReturn(40L, 32L);
        when(dashboardMapper.countMessagesBefore(any())).thenReturn(900L, 840L);
        when(dashboardMapper.countMessagesBetween(any(), any())).thenReturn(80L, 50L);

        DashboardOverviewVO overview = facadeService.getOverview("24h");

        assertThat(overview.getWindow()).isEqualTo("24h");
        assertThat(overview.getCompareWindow()).isEqualTo("prev_24h");
        assertThat(overview.getUpdatedAt()).isPositive();
        assertThat(overview.getKpis().getTotalUsers().getValue()).isEqualTo(120L);
        assertThat(overview.getKpis().getTotalUsers().getDelta()).isEqualTo(20L);
        assertThat(overview.getKpis().getTotalUsers().getDeltaPct()).isEqualTo(20.0);
        assertThat(overview.getKpis().getActiveUsers().getValue()).isEqualTo(25L);
        assertThat(overview.getKpis().getActiveUsers().getDelta()).isEqualTo(5L);
        assertThat(overview.getKpis().getActiveUsers().getDeltaPct()).isEqualTo(25.0);
        assertThat(overview.getKpis().getTotalSessions().getValue()).isEqualTo(300L);
        assertThat(overview.getKpis().getTotalSessions().getDelta()).isEqualTo(20L);
        assertThat(overview.getKpis().getTotalSessions().getDeltaPct()).isNull();
        assertThat(overview.getKpis().getSessions24h().getValue()).isEqualTo(40L);
        assertThat(overview.getKpis().getSessions24h().getDelta()).isEqualTo(8L);
        assertThat(overview.getKpis().getSessions24h().getDeltaPct()).isEqualTo(25.0);
        assertThat(overview.getKpis().getTotalMessages().getValue()).isEqualTo(900L);
        assertThat(overview.getKpis().getTotalMessages().getDelta()).isEqualTo(60L);
        assertThat(overview.getKpis().getTotalMessages().getDeltaPct()).isNull();
        assertThat(overview.getKpis().getMessages24h().getValue()).isEqualTo(80L);
        assertThat(overview.getKpis().getMessages24h().getDelta()).isEqualTo(30L);
        assertThat(overview.getKpis().getMessages24h().getDeltaPct()).isEqualTo(60.0);

        verify(adminAccessService).requireAdmin();

        ArgumentCaptor<LocalDateTime> usersBeforeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(dashboardMapper, org.mockito.Mockito.times(2)).countUsersBefore(usersBeforeCaptor.capture());
        List<LocalDateTime> userBeforeArgs = usersBeforeCaptor.getAllValues();
        assertThat(Duration.between(userBeforeArgs.get(1), userBeforeArgs.get(0)))
                .isEqualTo(Duration.ofHours(24));

        ArgumentCaptor<LocalDateTime> activeStartCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> activeEndCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(dashboardMapper, org.mockito.Mockito.times(2))
                .countActiveUsersBetween(activeStartCaptor.capture(), activeEndCaptor.capture());
        List<LocalDateTime> activeStarts = activeStartCaptor.getAllValues();
        List<LocalDateTime> activeEnds = activeEndCaptor.getAllValues();
        assertThat(Duration.between(activeStarts.get(0), activeEnds.get(0)))
                .isEqualTo(Duration.ofHours(24));
        assertThat(Duration.between(activeStarts.get(1), activeEnds.get(1)))
                .isEqualTo(Duration.ofHours(24));
        assertThat(activeEnds.get(1)).isEqualTo(activeStarts.get(0));
    }

    @Test
    void shouldAssemblePerformanceAndPercentilesFromAggregateData() {
        Map<String, Object> aggregate = new HashMap<>();
        aggregate.put("totalCount", 10L);
        aggregate.put("successCount", 8L);
        aggregate.put("errorCount", 2L);
        aggregate.put("slowCount", 3L);
        aggregate.put("noKnowledgeCount", 2L);
        aggregate.put("avgLatencyMs", 1534.6d);
        when(dashboardMapper.selectPerformanceAggregate(any(), any())).thenReturn(aggregate);
        when(dashboardMapper.selectSuccessfulDurations(any(), any())).thenReturn(
                List.of(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L, 1000L)
        );

        DashboardPerformanceVO performance = facadeService.getPerformance(null);

        assertThat(performance.getWindow()).isEqualTo("24h");
        assertThat(performance.getAvgLatencyMs()).isEqualTo(1535L);
        assertThat(performance.getP95LatencyMs()).isEqualTo(1000L);
        assertThat(performance.getSuccessRate()).isEqualTo(80.0);
        assertThat(performance.getErrorRate()).isEqualTo(20.0);
        assertThat(performance.getNoDocRate()).isEqualTo(25.0);
        assertThat(performance.getSlowRate()).isEqualTo(30.0);

        verify(adminAccessService).requireAdmin();

        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(dashboardMapper).selectPerformanceAggregate(startCaptor.capture(), endCaptor.capture());
        assertThat(Duration.between(startCaptor.getValue(), endCaptor.getValue()))
                .isEqualTo(Duration.ofHours(24));
    }

    @Test
    void shouldBuildDailyLatencyTrendAndFillMissingBucketsWithZero() {
        when(dashboardMapper.selectLatencyTrend(any(), any(), anyString())).thenAnswer(invocation -> {
            LocalDateTime start = invocation.getArgument(0);
            LocalDateTime firstBucket = start.toLocalDate().atStartOfDay();
            return List.of(
                    row(firstBucket, "avgLatencyMs", 110.0d, "p95LatencyMs", 250.0d),
                    row(firstBucket.plusDays(2), "avgLatencyMs", 95.0d, "p95LatencyMs", 180.0d)
            );
        });

        DashboardTrendsVO trends = facadeService.getTrends("avgLatency", "7d", null);

        assertThat(trends.getMetric()).isEqualTo("avgLatency");
        assertThat(trends.getWindow()).isEqualTo("7d");
        assertThat(trends.getGranularity()).isEqualTo("day");
        assertThat(trends.getSeries()).extracting(DashboardTrendsVO.DashboardSeriesVO::getName)
                .containsExactly("Avg latency", "P95 latency");

        List<DashboardTrendsVO.DashboardPointVO> avgSeries = trends.getSeries().get(0).getData();
        List<DashboardTrendsVO.DashboardPointVO> p95Series = trends.getSeries().get(1).getData();
        assertThat(avgSeries).hasSize(8);
        assertThat(p95Series).hasSize(8);
        assertThat(avgSeries).extracting(DashboardTrendsVO.DashboardPointVO::getValue)
                .startsWith(110.0d, 0.0d, 95.0d);
        assertThat(p95Series).extracting(DashboardTrendsVO.DashboardPointVO::getValue)
                .startsWith(250.0d, 0.0d, 180.0d);

        verify(adminAccessService).requireAdmin();

        ArgumentCaptor<String> bucketCaptor = ArgumentCaptor.forClass(String.class);
        verify(dashboardMapper).selectLatencyTrend(any(), any(), bucketCaptor.capture());
        assertThat(bucketCaptor.getValue()).isEqualTo("day");
    }

    @Test
    void shouldBuildHourlyQualityTrendUsingRates() {
        when(dashboardMapper.selectQualityTrend(any(), any(), anyString())).thenAnswer(invocation -> {
            LocalDateTime start = invocation.getArgument(0);
            LocalDateTime firstBucket = start.truncatedTo(ChronoUnit.HOURS);
            return List.of(
                    row(firstBucket,
                            "totalCount", 4L,
                            "successCount", 3L,
                            "errorCount", 1L,
                            "noKnowledgeCount", 1L),
                    row(firstBucket.plusHours(2),
                            "totalCount", 2L,
                            "successCount", 2L,
                            "errorCount", 0L,
                            "noKnowledgeCount", 0L)
            );
        });

        DashboardTrendsVO trends = facadeService.getTrends("quality", "24h", null);

        assertThat(trends.getMetric()).isEqualTo("quality");
        assertThat(trends.getWindow()).isEqualTo("24h");
        assertThat(trends.getGranularity()).isEqualTo("hour");
        assertThat(trends.getSeries()).extracting(DashboardTrendsVO.DashboardSeriesVO::getName)
                .containsExactly("Error rate", "Knowledge miss rate");

        List<Double> errorRateValues = trends.getSeries().get(0).getData().stream()
                .map(DashboardTrendsVO.DashboardPointVO::getValue)
                .toList();
        List<Double> missRateValues = trends.getSeries().get(1).getData().stream()
                .map(DashboardTrendsVO.DashboardPointVO::getValue)
                .toList();
        assertThat(errorRateValues).startsWith(25.0d, 0.0d, 0.0d);
        assertThat(missRateValues).startsWith(33.3d, 0.0d, 0.0d);

        verify(adminAccessService).requireAdmin();

        ArgumentCaptor<String> bucketCaptor = ArgumentCaptor.forClass(String.class);
        verify(dashboardMapper).selectQualityTrend(any(), any(), bucketCaptor.capture());
        assertThat(bucketCaptor.getValue()).isEqualTo("hour");
    }

    private static Map<String, Object> row(LocalDateTime bucket, Object... keyValuePairs) {
        Map<String, Object> row = new HashMap<>();
        row.put("bucket", bucket);
        for (int index = 0; index < keyValuePairs.length; index += 2) {
            row.put(String.valueOf(keyValuePairs[index]), keyValuePairs[index + 1]);
        }
        return row;
    }
}
