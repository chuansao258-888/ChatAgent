package com.yulong.chatagent.rag.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Records a repeatable golden-sample baseline for Phase 8 without changing the
 * original whole-PDF dispatch behavior.
 */
@Tag("golden")
class GoldenPdfPerformanceBaselineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GOLDEN_ROOT = "golden-pdfs";
    private static final List<GoldenBaselineCase> BASELINE_CASES = List.of(
            new GoldenBaselineCase("heading-01", "headings/heading-01.pdf"),
            new GoldenBaselineCase("scanned-01", "scanned/scanned-01.pdf"),
            new GoldenBaselineCase("mixed-01", "mixed/mixed-01.pdf")
    );

    @Test
    void shouldRecordPhase8BaselineWithoutChangingParseOutput() throws Exception {
        List<BaselineObservation> observations = new ArrayList<>();
        for (GoldenBaselineCase testCase : BASELINE_CASES) {
            observations.add(measure(testCase));
        }

        Path reportPath = Path.of("target", "phase8-baseline", "golden-pdf-performance-baseline.json");
        Files.createDirectories(reportPath.getParent());
        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(reportPath.toFile(), new BaselineReport(Instant.now().toString(), observations));

        for (BaselineObservation observation : observations) {
            double totalLookups = observation.cacheMisses() + observation.cacheHits();
            assertThat(totalLookups)
                    .as("%s: cache should be consulted exactly once per visual-track page per parse",
                            observation.sample())
                    .isEqualTo(observation.visualTrackPageCount() * 2.0d);
        }
    }

    private static BaselineObservation measure(GoldenBaselineCase testCase) throws Exception {
        Path pdfPath = resolveGoldenRoot().resolve(testCase.relativePdfPath());
        byte[] pdfBytes = Files.readAllBytes(pdfPath);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        VdpPageCacheService cacheService = new VdpPageCacheService(
                new ObjectMapper(),
                new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class),
                new VdpCacheProperties(),
                meterRegistry
        );
        PdfDocumentParser parser = new PdfDocumentParser(
                new NoopVdpEngine(),
                cacheService,
                Runnable::run,
                Runnable::run,
                150,
                80,
                2,
                2,
                5000L,
                120000L,
                144f,
                meterRegistry
        );
        Map<String, Object> options = Map.of(
                "pipelineSource", PipelineSource.SESSION,
                "sessionId", "golden-baseline-" + testCase.name(),
                "documentCacheKey", "golden-pdf:" + testCase.name(),
                "fileSizeBytes", pdfBytes.length
        );

        long firstStartedAt = System.nanoTime();
        ParseResult first = parser.parse(pdfBytes, "application/pdf", options);
        long firstElapsedNs = System.nanoTime() - firstStartedAt;

        long secondStartedAt = System.nanoTime();
        ParseResult second = parser.parse(pdfBytes, "application/pdf", options);
        long secondElapsedNs = System.nanoTime() - secondStartedAt;

        assertThat(second).usingRecursiveComparison().isEqualTo(first);

        int visualTrackPageCount = visualTrackPageCount(first);
        double cacheMisses = counterValue(meterRegistry, "vdp.cache.miss", "layer", "page");
        double cacheHits = counterValue(meterRegistry, "vdp.cache.hit", "layer", "page");

        Timer latencyTimer = meterRegistry.find("vdp.document.parse.latency")
                .tags("pipelineSource", "SESSION", "extractionMode", first.getExtractionMode())
                .timer();
        long latencySamples = latencyTimer == null ? 0L : latencyTimer.count();
        double totalLatencyMs = latencyTimer == null ? 0.0d : latencyTimer.totalTime(TimeUnit.MILLISECONDS);

        return new BaselineObservation(
                testCase.name(),
                first.getSegments().size(),
                first.getExtractionMode(),
                visualTrackPageCount,
                roundMillis(firstElapsedNs),
                roundMillis(secondElapsedNs),
                round(totalLatencyMs),
                latencySamples,
                cacheMisses,
                cacheHits
        );
    }

    private static Path resolveGoldenRoot() {
        java.net.URL resource = GoldenPdfPerformanceBaselineTest.class.getClassLoader().getResource(GOLDEN_ROOT);
        if (resource != null) {
            try {
                return Path.of(resource.toURI());
            } catch (Exception ignored) {
                // fall through
            }
        }
        Path direct = Path.of("src/test/resources", GOLDEN_ROOT);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        throw new IllegalStateException("Cannot locate golden-pdfs/ directory on classpath or filesystem");
    }

    private static int visualTrackPageCount(ParseResult result) {
        if (result == null || result.getMetadata() == null) {
            return 0;
        }
        Object value = result.getMetadata().get("visualTrackPageCount");
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static double counterValue(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0d : round(counter.count());
    }

    private static double roundMillis(long elapsedNs) {
        return round(elapsedNs / 1_000_000.0d);
    }

    private static double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    record GoldenBaselineCase(String name, String relativePdfPath) {
    }

    record BaselineObservation(String sample,
                               int segmentCount,
                               String extractionMode,
                               int visualTrackPageCount,
                               double firstParseMs,
                               double secondParseMs,
                               double parseLatencyTotalMs,
                               long latencySamples,
                               double cacheMisses,
                               double cacheHits) {
    }

    record BaselineReport(String generatedAt, List<BaselineObservation> samples) {
    }
}
