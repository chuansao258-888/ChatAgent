package com.yulong.chatagent.rag.parser;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke validation against a real MinerU service.
 *
 * <p>This class intentionally does not use the default *Test suffix, so it stays out of normal
 * surefire discovery. Run it explicitly when a real MinerU environment is available.
 */
@Tag("mineru-smoke")
@EnabledIfEnvironmentVariable(named = "CHATAGENT_RAG_VDP_MINERU_SMOKE", matches = "(?i:true|1|yes)")
class MinerUVdpEngineSmoke {

    @Test
    void shouldParseGoldenTablePdfAgainstConfiguredMinerUService() throws Exception {
        MinerUProperties properties = new MinerUProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(requiredSetting("CHATAGENT_RAG_VDP_MINERU_BASE_URL"));
        properties.setBearerToken(optionalSetting("CHATAGENT_RAG_VDP_MINERU_BEARER_TOKEN"));
        properties.setVersion(optionalSetting("CHATAGENT_RAG_VDP_MINERU_VERSION", properties.getVersion()));
        properties.setPollIntervalMs(longSetting("CHATAGENT_RAG_VDP_MINERU_POLL_INTERVAL_MS", properties.getPollIntervalMs()));
        properties.setMaxPollAttempts(intSetting("CHATAGENT_RAG_VDP_MINERU_MAX_POLL_ATTEMPTS", properties.getMaxPollAttempts()));
        properties.setSubmitTimeoutMs(longSetting("CHATAGENT_RAG_VDP_MINERU_SUBMIT_TIMEOUT_MS", properties.getSubmitTimeoutMs()));
        properties.setPollTimeoutMs(longSetting("CHATAGENT_RAG_VDP_MINERU_POLL_TIMEOUT_MS", properties.getPollTimeoutMs()));

        MinerUVdpEngine engine = new MinerUVdpEngine(
                WebClient.builder(),
                properties,
                new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class)
        );

        Path pdfPath = resolvePdfPath();
        byte[] pdfBytes = Files.readAllBytes(pdfPath);
        List<VdpPageResult> results = engine.parsePagesAsync(
                () -> new ByteArrayInputStream(pdfBytes),
                List.of(0),
                new VdpOptions(false, "en", null),
                Runnable::run
        ).join();

        assertThat(results)
                .as("MinerU smoke result for %s", pdfPath.getFileName())
                .isNotEmpty();
        assertThat(results).extracting(VdpPageResult::pageIndex).allMatch(index -> index >= 0);
        assertThat(results)
                .anySatisfy(result -> {
                    assertThat(result.status()).isNotEqualTo(VdpPageStatus.FAILED);
                    assertThat(result.markdown()).isNotBlank();
                });
    }

    private static Path resolvePdfPath() {
        String override = optionalSetting("CHATAGENT_RAG_VDP_MINERU_SMOKE_PDF");
        if (StringUtils.hasText(override)) {
            return Path.of(override.trim());
        }
        return Path.of(
                "src",
                "test",
                "resources",
                "golden-pdfs",
                "tables",
                "table-01.pdf"
        );
    }

    private static String requiredSetting(String name) {
        String value = optionalSetting(name);
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Missing required MinerU smoke setting: " + name);
        }
        return value.trim();
    }

    private static String optionalSetting(String name) {
        return optionalSetting(name, null);
    }

    private static String optionalSetting(String name, String fallback) {
        String systemValue = System.getProperty(name);
        if (StringUtils.hasText(systemValue)) {
            return systemValue.trim();
        }
        String envValue = System.getenv(name);
        if (StringUtils.hasText(envValue)) {
            return envValue.trim();
        }
        return fallback;
    }

    private static long longSetting(String name, long fallback) {
        String raw = optionalSetting(name);
        return StringUtils.hasText(raw) ? Long.parseLong(raw) : fallback;
    }

    private static int intSetting(String name, int fallback) {
        String raw = optionalSetting(name);
        return StringUtils.hasText(raw) ? Integer.parseInt(raw) : fallback;
    }
}
