package com.yulong.chatagent.rag.parser;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Shared VDP result helpers for failure shaping and low-cardinality metrics.
 */
final class PdfVdpResultSupport {

    private final MeterRegistry meterRegistry;

    PdfVdpResultSupport(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    static VdpPageResult failedPageResult(int pageIndex, String reason) {
        String normalizedReason = StringUtils.hasText(reason) ? reason.trim() : "Unknown visual parsing failure";
        return new VdpPageResult(
                pageIndex,
                "",
                VdpPageStatus.FAILED,
                Map.of(
                        "contentOrigin", "VDP_TRANSCRIBED",
                        "visualType", "IMAGE",
                        "degraded", true,
                        "engineId", "pdf-visual-track",
                        "interpretiveNote", "[此页内容解析失败]: " + normalizedReason
                )
        );
    }

    void recordPageTimeout(String engineId) {
        VdpMetricsSupport.increment(
                meterRegistry,
                "vdp.page.timeout",
                "engineId",
                VdpMetricsSupport.tagValue(engineId, "unknown")
        );
    }

    void recordResolvedVisualPage(String engineId, VdpPageResult result) {
        if (result == null || result.status() == null) {
            return;
        }
        String metricName = switch (result.status()) {
            case SUCCESS -> "vdp.page.success";
            case DEGRADED -> "vdp.page.degraded";
            case FAILED -> "vdp.page.failed";
        };
        VdpMetricsSupport.increment(
                meterRegistry,
                metricName,
                "engineId",
                VdpMetricsSupport.tagValue(engineId, "unknown")
        );
    }
}
