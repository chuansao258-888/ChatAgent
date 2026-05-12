package com.yulong.chatagent.rag.parser;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class VdpEngineRouterTest {

    @Test
    void shouldPreferConfiguredPageAndBatchEnginesWithoutBeanOrderDependence() {
        VdpEngineRoutingProperties properties = new VdpEngineRoutingProperties();
        properties.setPreferredPageImageEngine("vlm");
        properties.setPreferredBatchEngine("mineru");
        NoopVdpEngine noopEngine = new NoopVdpEngine();
        VdpEngineRouter router = new VdpEngineRouter(
                List.of(
                        stubEngine("zzz-batch", EnumSet.of(VdpMode.PDF_PAGE_BATCH)),
                        stubEngine("mineru", EnumSet.of(VdpMode.PDF_PAGE_BATCH)),
                        stubEngine("vlm", EnumSet.of(VdpMode.PAGE_IMAGE)),
                        noopEngine
                ),
                properties,
                noopEngine
        );

        assertThat(router.resolveForPageImage(PipelineSource.SESSION).engineId()).isEqualTo("vlm");
        assertThat(router.resolveForBatch(PipelineSource.KNOWLEDGE).engineId()).isEqualTo("mineru");
    }

    @Test
    void shouldDisableBatchRoutingWhenKnowledgeBatchPreferredIsFalse() {
        VdpEngineRoutingProperties properties = new VdpEngineRoutingProperties();
        properties.setKnowledgeBatchPreferred(false);
        NoopVdpEngine noopEngine = new NoopVdpEngine();
        VdpEngineRouter router = new VdpEngineRouter(
                List.of(stubEngine("mineru", EnumSet.of(VdpMode.PDF_PAGE_BATCH)), noopEngine),
                properties,
                noopEngine
        );

        assertThat(router.resolveForBatch(PipelineSource.KNOWLEDGE)).isNull();
        // supportsBatchDispatch 是旧便捷入口；当前直接用 resolveForBatch(null) 表达不支持。
    }

    @Test
    void shouldSkipDisabledPreferredEngineAndFallbackToAvailablePageEngine() {
        VdpEngineRoutingProperties properties = new VdpEngineRoutingProperties();
        properties.setPreferredPageImageEngine("vlm");
        properties.setDisabledEngines(java.util.Set.of("vlm"));
        NoopVdpEngine noopEngine = new NoopVdpEngine();
        VdpEngineRouter router = new VdpEngineRouter(
                List.of(
                        stubEngine("page-backup", EnumSet.of(VdpMode.PAGE_IMAGE)),
                        stubEngine("vlm", EnumSet.of(VdpMode.PAGE_IMAGE)),
                        noopEngine
                ),
                properties,
                noopEngine
        );

        assertThat(router.resolveForPageImage(PipelineSource.SESSION).engineId()).isEqualTo("page-backup");
    }

    @Test
    void shouldKeepFirstEngineWhenEngineIdsConflict() {
        VdpEngineRoutingProperties properties = new VdpEngineRoutingProperties();
        properties.setPreferredPageImageEngine("dup");
        NoopVdpEngine noopEngine = new NoopVdpEngine();
        VdpEngine first = stubEngine("dup", EnumSet.of(VdpMode.PAGE_IMAGE), "v-first");
        VdpEngine second = stubEngine("dup", EnumSet.of(VdpMode.PAGE_IMAGE), "v-second");
        VdpEngineRouter router = new VdpEngineRouter(List.of(first, second, noopEngine), properties, noopEngine);

        assertThat(router.resolveForPageImage(PipelineSource.SESSION).promptVersion()).isEqualTo("v-first");
    }

    @Test
    void shouldRecordResolvedEngineMetrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        NoopVdpEngine noopEngine = new NoopVdpEngine();
        VdpEngineRouter router = new VdpEngineRouter(
                List.of(stubEngine("vlm", EnumSet.of(VdpMode.PAGE_IMAGE)), noopEngine),
                new VdpEngineRoutingProperties(),
                noopEngine,
                meterRegistry
        );

        router.resolveForPageImage(PipelineSource.SESSION);

        assertThat(meterRegistry.get("vdp.engine.resolved")
                .tags("engineId", "vlm", "mode", "PAGE_IMAGE", "pipelineSource", "SESSION")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    private VdpEngine stubEngine(String engineId, EnumSet<VdpMode> modes) {
        return stubEngine(engineId, modes, "v1");
    }

    private VdpEngine stubEngine(String engineId, EnumSet<VdpMode> modes, String promptVersion) {
        return new VdpEngine() {
            @Override
            public VdpPageResult parsePage(Supplier<InputStream> imageStream, String imageFormat, VdpOptions options) {
                return new VdpPageResult(0, "", VdpPageStatus.SUCCESS, java.util.Map.of("engineId", engineId));
            }

            @Override
            public List<VdpPageResult> parsePages(Supplier<InputStream> pdfStream, List<Integer> pageIndices, VdpOptions options) {
                return List.of();
            }

            @Override
            public String engineId() {
                return engineId;
            }

            @Override
            public String promptVersion() {
                return promptVersion;
            }

            @Override
            public EnumSet<VdpMode> supportedModes() {
                return modes;
            }
        };
    }
}
