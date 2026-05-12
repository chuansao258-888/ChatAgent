package com.yulong.chatagent.rag.parser;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Selects the appropriate visual parsing engine for the current pipeline and mode.
 */
@Component
@Slf4j
public class VdpEngineRouter {

    private final Map<String, VdpEngine> engineMap;
    private final VdpEngineRoutingProperties properties;
    private final NoopVdpEngine noopEngine;
    private final MeterRegistry meterRegistry;

    @Autowired
    public VdpEngineRouter(List<VdpEngine> engines,
                           VdpEngineRoutingProperties properties,
                           NoopVdpEngine noopEngine,
                           ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(engines, properties, noopEngine, meterRegistryProvider.getIfAvailable());
    }

    VdpEngineRouter(List<VdpEngine> engines,
                    VdpEngineRoutingProperties properties,
                    NoopVdpEngine noopEngine) {
        this(engines, properties, noopEngine, (MeterRegistry) null);
    }

    VdpEngineRouter(List<VdpEngine> engines,
                    VdpEngineRoutingProperties properties,
                    NoopVdpEngine noopEngine,
                    MeterRegistry meterRegistry) {
        this.engineMap = buildEngineMap(engines);
        this.properties = properties == null ? new VdpEngineRoutingProperties() : properties;
        this.noopEngine = noopEngine == null ? new NoopVdpEngine() : noopEngine;
        this.meterRegistry = meterRegistry;
    }

    VdpEngineRouter(List<VdpEngine> engines,
                    VdpEngineRoutingProperties properties) {
        this(engines, properties, new NoopVdpEngine());
    }

    static VdpEngineRouter forTesting(VdpEngine... engines) {
        NoopVdpEngine noop = new NoopVdpEngine();
        boolean hasNoop = engines != null && java.util.Arrays.stream(engines)
                .filter(Objects::nonNull)
                .anyMatch(engine -> engine instanceof NoopVdpEngine || "noop".equalsIgnoreCase(normalizeEngineId(engine)));
        List<VdpEngine> engineList = engines == null || engines.length == 0
                ? List.of(noop)
                : hasNoop
                ? java.util.Arrays.stream(engines).filter(Objects::nonNull).toList()
                : java.util.stream.Stream.concat(java.util.Arrays.stream(engines), java.util.stream.Stream.of(noop))
                .filter(Objects::nonNull)
                .toList();
        return new VdpEngineRouter(engineList, new VdpEngineRoutingProperties(), noop);
    }

    public VdpEngine resolveForPageImage(PipelineSource source) {
        VdpEngine engine = doResolveForPageImage();
        recordResolved(engine, VdpMode.PAGE_IMAGE, source);
        return engine;
    }

    public VdpEngine resolveForBatch(PipelineSource source) {
        VdpEngine engine = doResolveForBatch(source);
        if (engine != null) {
            recordResolved(engine, VdpMode.PDF_PAGE_BATCH, source);
        }
        return engine;
    }

    // 旧便捷判断已停用：当前生产路径直接调用 resolveForBatch(source)，
    // 返回 null 就表示不走批量 VDP 分发。
    // public boolean supportsBatchDispatch(PipelineSource source) {
    //     return doResolveForBatch(source) != null;
    // }

    private VdpEngine doResolveForPageImage() {
        VdpEngine preferred = findPreferred(properties.getPreferredPageImageEngine(), VdpMode.PAGE_IMAGE);
        if (preferred != null) {
            return preferred;
        }
        return engineMap.values().stream()
                .filter(this::isEnabled)
                .filter(engine -> engine.supportedModes().contains(VdpMode.PAGE_IMAGE))
                .findFirst()
                .orElse(noopEngine);
    }

    private VdpEngine doResolveForBatch(PipelineSource source) {
        return switch (source) {
            case SESSION -> null;
            case KNOWLEDGE -> {
                if (!properties.isKnowledgeBatchPreferred()) {
                    yield null;
                }
                VdpEngine preferred = findPreferred(properties.getPreferredBatchEngine(), VdpMode.PDF_PAGE_BATCH);
                if (preferred != null) {
                    yield preferred;
                }
                yield engineMap.values().stream()
                        .filter(this::isEnabled)
                        .filter(engine -> engine.supportedModes().contains(VdpMode.PDF_PAGE_BATCH))
                        .findFirst()
                        .orElse(null);
            }
        };
    }

    private VdpEngine findPreferred(String preferredId, VdpMode requiredMode) {
        if (!StringUtils.hasText(preferredId)) {
            return null;
        }
        VdpEngine engine = engineMap.get(preferredId.trim());
        if (engine == null || !isEnabled(engine) || !engine.supportedModes().contains(requiredMode)) {
            return null;
        }
        return engine;
    }

    private boolean isEnabled(VdpEngine engine) {
        if (engine == null) {
            return false;
        }
        String engineId = normalizeEngineId(engine);
        return !properties.getDisabledEngines().contains(engineId);
    }

    private Map<String, VdpEngine> buildEngineMap(List<VdpEngine> engines) {
        LinkedHashMap<String, VdpEngine> ordered = new LinkedHashMap<>();
        if (engines == null) {
            return ordered;
        }
        engines.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(VdpEngineRouter::normalizeEngineId))
                .forEach(engine -> {
                    String engineId = normalizeEngineId(engine);
                    VdpEngine previous = ordered.putIfAbsent(engineId, engine);
                    if (previous != null) {
                        log.warn("Ignoring duplicate VDP engine registration: engineId={}, kept={}, skipped={}",
                                engineId,
                                previous.getClass().getName(),
                                engine.getClass().getName());
                    }
                });
        return Collections.unmodifiableMap(ordered);
    }

    private static String normalizeEngineId(VdpEngine engine) {
        String engineId = engine == null ? null : engine.engineId();
        return StringUtils.hasText(engineId) ? engineId.trim() : "unknown";
    }

    private void recordResolved(VdpEngine engine, VdpMode mode, PipelineSource source) {
        if (engine == null) {
            return;
        }
        VdpMetricsSupport.increment(
                meterRegistry,
                "vdp.engine.resolved",
                "engineId", VdpMetricsSupport.tagValue(engine.engineId(), "unknown"),
                "mode", mode == null ? "UNKNOWN" : mode.name(),
                "pipelineSource", VdpMetricsSupport.pipelineSourceTag(source)
        );
    }
}
