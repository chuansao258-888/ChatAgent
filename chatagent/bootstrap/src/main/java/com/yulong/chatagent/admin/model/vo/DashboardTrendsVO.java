package com.yulong.chatagent.admin.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Dashboard time-series payload.
 */
@Data
@Builder
public class DashboardTrendsVO {

    private String metric;

    private String window;

    private String granularity;

    private List<DashboardSeriesVO> series;

    @Data
    @Builder
    public static class DashboardSeriesVO {
        private String name;
        private List<DashboardPointVO> data;
    }

    @Data
    @Builder
    public static class DashboardPointVO {
        private Long ts;
        private Double value;
    }
}
