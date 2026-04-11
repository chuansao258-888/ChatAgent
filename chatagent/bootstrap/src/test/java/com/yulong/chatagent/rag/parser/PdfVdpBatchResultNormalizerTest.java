package com.yulong.chatagent.rag.parser;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PdfVdpBatchResultNormalizerTest {

    @Test
    void shouldFillMissingBatchPageWithFailedResult() {
        PdfVdpCache cache = new PdfVdpCache(null, 144f);
        PdfVdpResultSupport resultSupport = new PdfVdpResultSupport(null);
        PdfVdpBatchResultNormalizer normalizer = new PdfVdpBatchResultNormalizer(cache, resultSupport);
        PdfVdpCache.PageCacheContext context = cache.buildContext(
                Map.of("documentCacheKey", "knowledge-content:doc-1"),
                PipelineSource.KNOWLEDGE,
                new VdpOptions(false, null, Map.of()),
                new NoopVdpEngine()
        );

        Map<Integer, VdpPageResult> results = normalizer.normalize(
                List.of(0, 2),
                List.of(
                        new VdpPageResult(0, "| ok |", VdpPageStatus.SUCCESS, Map.of("visualType", "TABLE")),
                        new VdpPageResult(1, "| extra |", VdpPageStatus.SUCCESS, Map.of("visualType", "TABLE"))
                ),
                context
        );

        assertThat(results).containsKeys(0, 2);
        assertThat(results.get(0).status()).isEqualTo(VdpPageStatus.SUCCESS);
        assertThat(results.get(2).status()).isEqualTo(VdpPageStatus.FAILED);
        assertThat(results.get(2).metadata().get("interpretiveNote").toString())
                .contains("Visual-track batch did not return a result");
    }

    @Test
    void shouldRecordTimeoutMetricWhenMarkingAllBatchPagesTimedOut() {
        PdfVdpCache cache = new PdfVdpCache(null, 144f);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        PdfVdpResultSupport resultSupport = new PdfVdpResultSupport(meterRegistry);
        PdfVdpBatchResultNormalizer normalizer = new PdfVdpBatchResultNormalizer(cache, resultSupport);
        PdfVdpCache.PageCacheContext context = cache.buildContext(
                Map.of("documentCacheKey", "knowledge-content:doc-2"),
                PipelineSource.KNOWLEDGE,
                new VdpOptions(false, null, Map.of()),
                new NoopVdpEngine()
        );

        Map<Integer, VdpPageResult> results = normalizer.markAllTimedOut(
                List.of(1, 3),
                context,
                "Visual-track batch timed out after 5000 ms"
        );

        assertThat(results).hasSize(2);
        assertThat(results.values()).allMatch(result -> result.status() == VdpPageStatus.FAILED);
        assertThat(meterRegistry.get("vdp.page.timeout")
                .tags("engineId", "noop")
                .counter()
                .count()).isEqualTo(2.0d);
    }
}
