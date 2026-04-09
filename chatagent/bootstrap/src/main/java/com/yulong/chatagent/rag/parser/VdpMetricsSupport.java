package com.yulong.chatagent.rag.parser;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Shared Micrometer helpers for VDP metrics.
 * Keep tag cardinality bounded to low-cardinality enums or stable identifiers only.
 */
@Slf4j
final class VdpMetricsSupport {

    private VdpMetricsSupport() {
    }

    static Timer.Sample start(MeterRegistry meterRegistry) {
        return meterRegistry == null ? null : Timer.start(meterRegistry);
    }

    static void stop(MeterRegistry meterRegistry, Timer.Sample sample, String name, String... tags) {
        if (meterRegistry == null || sample == null) {
            return;
        }
        if (!hasValidTagPairs(name, tags)) {
            return;
        }
        try {
            sample.stop(Timer.builder(name).tags(tags).register(meterRegistry));
        } catch (RuntimeException e) {
            log.warn("Failed to record timer metric: name={}, error={}", name, e.getMessage());
        }
    }

    static void increment(MeterRegistry meterRegistry, String name, String... tags) {
        if (meterRegistry == null) {
            return;
        }
        if (!hasValidTagPairs(name, tags)) {
            return;
        }
        try {
            meterRegistry.counter(name, tags).increment();
        } catch (RuntimeException e) {
            log.warn("Failed to increment counter metric: name={}, error={}", name, e.getMessage());
        }
    }

    static String tagValue(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    static String pipelineSourceTag(PipelineSource pipelineSource) {
        return pipelineSource == null ? PipelineSource.KNOWLEDGE.name() : pipelineSource.name();
    }

    static String pageStatusTag(VdpPageResult result) {
        return result == null || result.status() == null ? "UNKNOWN" : result.status().name();
    }

    static String aggregateStatus(List<VdpPageResult> results, Throwable error) {
        if (error != null) {
            return "FAILED";
        }
        if (results == null || results.isEmpty()) {
            return "SUCCESS";
        }
        boolean degraded = false;
        for (VdpPageResult result : results) {
            if (result == null || result.status() == null) {
                degraded = true;
                continue;
            }
            if (result.status() == VdpPageStatus.FAILED) {
                return "FAILED";
            }
            if (result.status() == VdpPageStatus.DEGRADED) {
                degraded = true;
            }
        }
        return degraded ? "DEGRADED" : "SUCCESS";
    }

    private static boolean hasValidTagPairs(String name, String... tags) {
        if (tags == null || tags.length == 0) {
            return true;
        }
        if (tags.length % 2 == 0) {
            return true;
        }
        log.warn("Skipping metric with odd tag array length: name={}, tagCount={}", name, tags.length);
        return false;
    }
}
