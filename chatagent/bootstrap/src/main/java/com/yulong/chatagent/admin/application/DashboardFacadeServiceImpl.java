package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.vo.DashboardMcpAlertVO;
import com.yulong.chatagent.admin.model.vo.DashboardMcpAlertsVO;
import com.yulong.chatagent.admin.model.vo.DashboardMcpPerformanceVO;
import com.yulong.chatagent.admin.model.vo.DashboardMcpServerMetricVO;
import com.yulong.chatagent.admin.model.vo.DashboardOverviewVO;
import com.yulong.chatagent.admin.model.vo.DashboardPerformanceVO;
import com.yulong.chatagent.admin.model.vo.DashboardTrendsVO;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.admin.port.McpToolCatalogRepository;
import com.yulong.chatagent.mcp.metrics.McpMetricsRecorder;
import com.yulong.chatagent.mcp.metrics.McpServerMetricsSnapshot;
import com.yulong.chatagent.mcp.runtime.McpRolloutPolicy;
import com.yulong.chatagent.support.dto.McpAlertEventDTO;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import com.yulong.chatagent.support.persistence.mapper.DashboardMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates dashboard metrics directly from business tables for the admin console.
 */
@Service
public class DashboardFacadeServiceImpl implements DashboardFacadeService {

    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    private final AdminAccessService adminAccessService;
    private final DashboardMapper dashboardMapper;
    private final McpFeatureFlag mcpFeatureFlag;
    private final McpServerRepository mcpServerRepository;
    private final McpToolCatalogRepository mcpToolCatalogRepository;
    private final McpServerReferenceInspector referenceInspector;
    private final McpMetricsRecorder mcpMetricsRecorder;
    private final McpAlertService mcpAlertService;
    private final McpRolloutPolicy mcpRolloutPolicy;

    @Autowired
    public DashboardFacadeServiceImpl(AdminAccessService adminAccessService,
                                      DashboardMapper dashboardMapper,
                                      McpFeatureFlag mcpFeatureFlag,
                                      McpServerRepository mcpServerRepository,
                                      McpToolCatalogRepository mcpToolCatalogRepository,
                                      McpServerReferenceInspector referenceInspector,
                                      McpMetricsRecorder mcpMetricsRecorder,
                                      McpAlertService mcpAlertService,
                                      McpRolloutPolicy mcpRolloutPolicy) {
        this.adminAccessService = adminAccessService;
        this.dashboardMapper = dashboardMapper;
        this.mcpFeatureFlag = mcpFeatureFlag;
        this.mcpServerRepository = mcpServerRepository;
        this.mcpToolCatalogRepository = mcpToolCatalogRepository;
        this.referenceInspector = referenceInspector;
        this.mcpMetricsRecorder = mcpMetricsRecorder;
        this.mcpAlertService = mcpAlertService;
        this.mcpRolloutPolicy = mcpRolloutPolicy;
    }

    DashboardFacadeServiceImpl(AdminAccessService adminAccessService, DashboardMapper dashboardMapper) {
        this(adminAccessService, dashboardMapper, null, null, null, null, null, null, null);
    }

    @Override
    public DashboardOverviewVO getOverview(String window) {
        adminAccessService.requireAdmin();
        DashboardWindow dashboardWindow = DashboardWindow.resolve(window, DashboardWindow.LAST_24_HOURS);
        LocalDateTime now = LocalDateTime.now();
        WindowRange current = dashboardWindow.currentRange(now);
        WindowRange previous = dashboardWindow.previousRange(now);

        long totalUsers = safeLong(dashboardMapper.countUsersBefore(current.end()));
        long totalUsersPrevious = safeLong(dashboardMapper.countUsersBefore(current.start()));
        long activeUsers = safeLong(dashboardMapper.countActiveUsersBetween(current.start(), current.end()));
        long activeUsersPrevious = safeLong(dashboardMapper.countActiveUsersBetween(previous.start(), previous.end()));
        long totalSessions = safeLong(dashboardMapper.countSessionsBefore(current.end()));
        long totalSessionsPrevious = safeLong(dashboardMapper.countSessionsBefore(current.start()));
        long sessionsInWindow = safeLong(dashboardMapper.countSessionsBetween(current.start(), current.end()));
        long sessionsInPreviousWindow = safeLong(dashboardMapper.countSessionsBetween(previous.start(), previous.end()));
        long totalMessages = safeLong(dashboardMapper.countMessagesBefore(current.end()));
        long totalMessagesPrevious = safeLong(dashboardMapper.countMessagesBefore(current.start()));
        long messagesInWindow = safeLong(dashboardMapper.countMessagesBetween(current.start(), current.end()));
        long messagesInPreviousWindow = safeLong(dashboardMapper.countMessagesBetween(previous.start(), previous.end()));

        return DashboardOverviewVO.builder()
                .window(dashboardWindow.value())
                .compareWindow(dashboardWindow.compareWindowValue())
                .updatedAt(toEpochMillis(now))
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

    @Override
    public DashboardPerformanceVO getPerformance(String window) {
        adminAccessService.requireAdmin();
        DashboardWindow dashboardWindow = DashboardWindow.resolve(window, DashboardWindow.LAST_24_HOURS);
        WindowRange current = dashboardWindow.currentRange(LocalDateTime.now());
        Map<String, Object> aggregate = dashboardMapper.selectPerformanceAggregate(current.start(), current.end());

        long totalCount = safeLong(valueOf(aggregate, "totalCount"));
        long successCount = safeLong(valueOf(aggregate, "successCount"));
        long errorCount = safeLong(valueOf(aggregate, "errorCount"));
        long slowCount = safeLong(valueOf(aggregate, "slowCount"));
        long noKnowledgeCount = safeLong(valueOf(aggregate, "noKnowledgeCount"));
        long avgLatencyMs = Math.round(safeDouble(valueOf(aggregate, "avgLatencyMs")));

        List<Long> durations = new ArrayList<>(dashboardMapper.selectSuccessfulDurations(current.start(), current.end()));
        durations.sort(Comparator.naturalOrder());

        return DashboardPerformanceVO.builder()
                .window(dashboardWindow.value())
                .avgLatencyMs(avgLatencyMs)
                .p95LatencyMs(percentile95(durations))
                .successRate(rate(successCount, totalCount))
                .errorRate(rate(errorCount, totalCount))
                .noDocRate(rate(noKnowledgeCount, successCount))
                .slowRate(rate(slowCount, totalCount))
                .mcp(buildMcpPerformance())
                .build();
    }

    @Override
    public DashboardTrendsVO getTrends(String metric, String window, String granularity) {
        adminAccessService.requireAdmin();
        DashboardMetric dashboardMetric = DashboardMetric.resolve(metric);
        DashboardWindow dashboardWindow = DashboardWindow.resolve(window, DashboardWindow.LAST_7_DAYS);
        DashboardGranularity resolvedGranularity = DashboardGranularity.resolve(granularity, dashboardWindow);
        WindowRange current = dashboardWindow.currentRange(LocalDateTime.now());

        return switch (dashboardMetric) {
            case SESSIONS -> buildSingleSeriesTrend(
                    dashboardMetric,
                    dashboardWindow,
                    resolvedGranularity,
                    current,
                    "Sessions",
                    dashboardMapper.selectSessionTrend(current.start(), current.end(), resolvedGranularity.bucketUnit())
            );
            case MESSAGES -> buildSingleSeriesTrend(
                    dashboardMetric,
                    dashboardWindow,
                    resolvedGranularity,
                    current,
                    "Messages",
                    dashboardMapper.selectMessageTrend(current.start(), current.end(), resolvedGranularity.bucketUnit())
            );
            case ACTIVE_USERS -> buildSingleSeriesTrend(
                    dashboardMetric,
                    dashboardWindow,
                    resolvedGranularity,
                    current,
                    "Active users",
                    dashboardMapper.selectActiveUserTrend(current.start(), current.end(), resolvedGranularity.bucketUnit())
            );
            case AVG_LATENCY -> buildLatencyTrend(dashboardWindow, resolvedGranularity, current);
            case QUALITY -> buildQualityTrend(dashboardWindow, resolvedGranularity, current);
        };
    }

    @Override
    public DashboardMcpAlertsVO getMcpAlerts(int limit) {
        adminAccessService.requireAdmin();
        if (mcpAlertService == null) {
            return DashboardMcpAlertsVO.builder()
                    .openAlertCount(0L)
                    .alerts(List.of())
                    .build();
        }
        int safeLimit = Math.max(1, limit);
        return DashboardMcpAlertsVO.builder()
                .openAlertCount(mcpAlertService.openAlertCount())
                .alerts(mcpAlertService.recentOpenAlerts(safeLimit).stream()
                        .map(this::toAlertVO)
                        .toList())
                .build();
    }

    private DashboardTrendsVO buildSingleSeriesTrend(DashboardMetric metric,
                                                     DashboardWindow window,
                                                     DashboardGranularity granularity,
                                                     WindowRange range,
                                                     String seriesName,
                                                     List<Map<String, Object>> rows) {
        Map<LocalDateTime, Double> valuesByBucket = toBucketValueMap(rows, "value");
        return DashboardTrendsVO.builder()
                .metric(metric.value())
                .window(window.value())
                .granularity(granularity.value())
                .series(List.of(DashboardTrendsVO.DashboardSeriesVO.builder()
                        .name(seriesName)
                        .data(buildPoints(range, granularity, valuesByBucket))
                        .build()))
                .build();
    }

    private DashboardTrendsVO buildLatencyTrend(DashboardWindow window,
                                                DashboardGranularity granularity,
                                                WindowRange range) {
        List<Map<String, Object>> rows =
                dashboardMapper.selectLatencyTrend(range.start(), range.end(), granularity.bucketUnit());
        Map<LocalDateTime, Double> avgByBucket = toBucketValueMap(rows, "avgLatencyMs");
        Map<LocalDateTime, Double> p95ByBucket = toBucketValueMap(rows, "p95LatencyMs");
        return DashboardTrendsVO.builder()
                .metric(DashboardMetric.AVG_LATENCY.value())
                .window(window.value())
                .granularity(granularity.value())
                .series(List.of(
                        DashboardTrendsVO.DashboardSeriesVO.builder()
                                .name("Avg latency")
                                .data(buildPoints(range, granularity, avgByBucket))
                                .build(),
                        DashboardTrendsVO.DashboardSeriesVO.builder()
                                .name("P95 latency")
                                .data(buildPoints(range, granularity, p95ByBucket))
                                .build()
                ))
                .build();
    }

    private DashboardTrendsVO buildQualityTrend(DashboardWindow window,
                                                DashboardGranularity granularity,
                                                WindowRange range) {
        List<Map<String, Object>> rows =
                dashboardMapper.selectQualityTrend(range.start(), range.end(), granularity.bucketUnit());
        Map<LocalDateTime, Double> errorRateByBucket = new HashMap<>();
        Map<LocalDateTime, Double> noKnowledgeRateByBucket = new HashMap<>();
        for (Map<String, Object> row : rows) {
            LocalDateTime bucket = safeLocalDateTime(valueOf(row, "bucket"));
            long totalCount = safeLong(valueOf(row, "totalCount"));
            long successCount = safeLong(valueOf(row, "successCount"));
            long errorCount = safeLong(valueOf(row, "errorCount"));
            long noKnowledgeCount = safeLong(valueOf(row, "noKnowledgeCount"));
            errorRateByBucket.put(bucket, rate(errorCount, totalCount));
            noKnowledgeRateByBucket.put(bucket, rate(noKnowledgeCount, successCount));
        }
        return DashboardTrendsVO.builder()
                .metric(DashboardMetric.QUALITY.value())
                .window(window.value())
                .granularity(granularity.value())
                .series(List.of(
                        DashboardTrendsVO.DashboardSeriesVO.builder()
                                .name("Error rate")
                                .data(buildPoints(range, granularity, errorRateByBucket))
                                .build(),
                        DashboardTrendsVO.DashboardSeriesVO.builder()
                                .name("Knowledge miss rate")
                                .data(buildPoints(range, granularity, noKnowledgeRateByBucket))
                                .build()
                ))
                .build();
    }

    private Map<LocalDateTime, Double> toBucketValueMap(List<Map<String, Object>> rows, String valueKey) {
        Map<LocalDateTime, Double> valuesByBucket = new HashMap<>();
        for (Map<String, Object> row : rows) {
            valuesByBucket.put(
                    safeLocalDateTime(valueOf(row, "bucket")),
                    safeDouble(valueOf(row, valueKey))
            );
        }
        return valuesByBucket;
    }

    private List<DashboardTrendsVO.DashboardPointVO> buildPoints(WindowRange range,
                                                                 DashboardGranularity granularity,
                                                                 Map<LocalDateTime, Double> valuesByBucket) {
        List<DashboardTrendsVO.DashboardPointVO> points = new ArrayList<>();
        LocalDateTime cursor = granularity.truncate(range.start());
        LocalDateTime lastBucket = granularity.truncate(range.end().minusNanos(1));
        while (!cursor.isAfter(lastBucket)) {
            points.add(DashboardTrendsVO.DashboardPointVO.builder()
                    .ts(toEpochMillis(cursor))
                    .value(valuesByBucket.getOrDefault(cursor, 0.0))
                    .build());
            cursor = granularity.increment(cursor);
        }
        return points;
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

    private Double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return roundToOneDecimal(numerator * 100.0 / denominator);
    }

    private Double roundToOneDecimal(double value) {
        return BigDecimal.valueOf(value)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private long percentile95(List<Long> durations) {
        if (durations.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(durations.size() * 0.95d) - 1;
        int safeIndex = Math.max(0, Math.min(index, durations.size() - 1));
        return durations.get(safeIndex);
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

    private double safeDouble(Object value) {
        if (value == null) {
            return 0.0d;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private LocalDateTime safeLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        throw new BizException("Unsupported dashboard bucket type: " + (value == null ? "null" : value.getClass().getName()));
    }

    private Object valueOf(Map<String, Object> row, String key) {
        return row == null ? null : row.get(key);
    }

    private DashboardMcpPerformanceVO buildMcpPerformance() {
        if (mcpServerRepository == null) {
            return DashboardMcpPerformanceVO.builder()
                    .enabled(mcpFeatureFlag == null || mcpFeatureFlag.isEnabled())
                    .rolloutMode(mcpRolloutPolicy == null ? "ALL" : mcpRolloutPolicy.mode().name())
                    .allowedAgentCount(mcpRolloutPolicy == null ? 0 : mcpRolloutPolicy.allowedAgentIds().size())
                    .openAlertCount(mcpAlertService == null ? 0L : mcpAlertService.openAlertCount())
                    .serverCount(0)
                    .servers(List.of())
                    .build();
        }
        List<McpServerDTO> servers = mcpServerRepository.findAll().stream()
                .sorted(Comparator.comparing(McpServerDTO::getSlug, Comparator.nullsLast(String::compareTo)))
                .toList();
        return DashboardMcpPerformanceVO.builder()
                .enabled(mcpFeatureFlag == null || mcpFeatureFlag.isEnabled())
                .rolloutMode(mcpRolloutPolicy == null ? "ALL" : mcpRolloutPolicy.mode().name())
                .allowedAgentCount(mcpRolloutPolicy == null ? 0 : mcpRolloutPolicy.allowedAgentIds().size())
                .openAlertCount(mcpAlertService == null ? 0L : mcpAlertService.openAlertCount())
                .serverCount(servers.size())
                .servers(servers.stream().map(this::toMcpServerMetricVO).toList())
                .build();
    }

    private DashboardMcpServerMetricVO toMcpServerMetricVO(McpServerDTO server) {
        McpServerMetricsSnapshot snapshot = mcpMetricsRecorder == null
                ? McpServerMetricsSnapshot.builder()
                .serverId(server == null ? null : server.getId())
                .totalCalls(0L)
                .successCount(0L)
                .failureCount(0L)
                .rateLimitedCount(0L)
                .avgLatencyMs(0L)
                .qps(0.0d)
                .errorRate(0.0d)
                .circuitState(0)
                .build()
                : mcpMetricsRecorder.snapshot(server);
        return DashboardMcpServerMetricVO.builder()
                .serverId(server.getId())
                .serverSlug(server.getSlug())
                .serverName(server.getName())
                .status(server.getStatus())
                .unresolvedReferenceCount(resolveUnresolvedReferenceCount(server))
                .totalCalls(snapshot.totalCalls())
                .successCount(snapshot.successCount())
                .failureCount(snapshot.failureCount())
                .rateLimitedCount(snapshot.rateLimitedCount())
                .avgLatencyMs(snapshot.avgLatencyMs())
                .qps(snapshot.qps())
                .errorRate(snapshot.errorRate())
                .circuitState(snapshot.circuitState())
                .lastErrorCode(server.getLastErrorCode())
                .lastTestedAt(server.getLastTestedAt())
                .lastSyncAt(server.getLastSyncAt())
                .build();
    }

    private int resolveUnresolvedReferenceCount(McpServerDTO server) {
        if (server == null || server.getId() == null || mcpToolCatalogRepository == null || referenceInspector == null) {
            return 0;
        }
        List<String> toolNames = mcpToolCatalogRepository.findByServerId(server.getId()).stream()
                .map(McpToolCatalogDTO::getExposedModelName)
                .filter(StringUtils::hasText)
                .toList();
        return referenceInspector.inspect(toolNames).size();
    }

    private DashboardMcpAlertVO toAlertVO(McpAlertEventDTO alertEvent) {
        return DashboardMcpAlertVO.builder()
                .id(alertEvent.getId())
                .serverId(alertEvent.getServerId())
                .serverSlug(alertEvent.getServerSlug())
                .toolName(alertEvent.getToolName())
                .alertType(alertEvent.getAlertType())
                .severity(alertEvent.getSeverity())
                .summary(alertEvent.getSummary())
                .detailsJson(alertEvent.getDetailsJson())
                .createdAt(alertEvent.getCreatedAt())
                .updatedAt(alertEvent.getUpdatedAt())
                .build();
    }

    private long toEpochMillis(LocalDateTime value) {
        return value.atZone(SYSTEM_ZONE).toInstant().toEpochMilli();
    }

    private record WindowRange(LocalDateTime start, LocalDateTime end) {
    }

    private enum DashboardMetric {
        SESSIONS("sessions"),
        MESSAGES("messages"),
        ACTIVE_USERS("activeUsers"),
        AVG_LATENCY("avgLatency"),
        QUALITY("quality");

        private final String value;

        DashboardMetric(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        static DashboardMetric resolve(String rawMetric) {
            if (!StringUtils.hasText(rawMetric)) {
                throw new BizException("metric is required");
            }
            return EnumSet.allOf(DashboardMetric.class).stream()
                    .filter(metric -> metric.value.equalsIgnoreCase(rawMetric.trim()))
                    .findFirst()
                    .orElseThrow(() -> new BizException("Unsupported dashboard metric: " + rawMetric));
        }
    }

    private enum DashboardWindow {
        LAST_24_HOURS("24h", Duration.ofHours(24)),
        LAST_7_DAYS("7d", Duration.ofDays(7)),
        LAST_30_DAYS("30d", Duration.ofDays(30));

        private final String value;
        private final Duration duration;

        DashboardWindow(String value, Duration duration) {
            this.value = value;
            this.duration = duration;
        }

        public String value() {
            return value;
        }

        public String compareWindowValue() {
            return "prev_" + value;
        }

        public Duration duration() {
            return duration;
        }

        WindowRange currentRange(LocalDateTime now) {
            return new WindowRange(now.minus(duration), now);
        }

        WindowRange previousRange(LocalDateTime now) {
            LocalDateTime currentStart = now.minus(duration);
            return new WindowRange(currentStart.minus(duration), currentStart);
        }

        static DashboardWindow resolve(String rawWindow, DashboardWindow defaultWindow) {
            if (!StringUtils.hasText(rawWindow)) {
                return defaultWindow;
            }
            return EnumSet.allOf(DashboardWindow.class).stream()
                    .filter(window -> window.value.equalsIgnoreCase(rawWindow.trim()))
                    .findFirst()
                    .orElseThrow(() -> new BizException("Unsupported dashboard window: " + rawWindow));
        }
    }

    private enum DashboardGranularity {
        HOUR("hour", ChronoUnit.HOURS),
        DAY("day", ChronoUnit.DAYS);

        private final String value;
        private final ChronoUnit unit;

        DashboardGranularity(String value, ChronoUnit unit) {
            this.value = value;
            this.unit = unit;
        }

        public String value() {
            return value;
        }

        public String bucketUnit() {
            return value;
        }

        LocalDateTime truncate(LocalDateTime value) {
            return switch (this) {
                case HOUR -> value.truncatedTo(ChronoUnit.HOURS);
                case DAY -> value.toLocalDate().atStartOfDay();
            };
        }

        LocalDateTime increment(LocalDateTime value) {
            return value.plus(1, unit);
        }

        static DashboardGranularity resolve(String rawGranularity, DashboardWindow window) {
            if (!StringUtils.hasText(rawGranularity)) {
                return window.duration().compareTo(Duration.ofHours(48)) <= 0 ? HOUR : DAY;
            }
            return EnumSet.allOf(DashboardGranularity.class).stream()
                    .filter(granularity -> granularity.value.equalsIgnoreCase(rawGranularity.trim()))
                    .findFirst()
                    .orElseThrow(() -> new BizException("Unsupported dashboard granularity: " + rawGranularity));
        }
    }
}
