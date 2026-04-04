package com.yulong.chatagent.rag.parser;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.EnumSet;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fallback VDP engine used when the real VLM-backed engine is disabled.
 */
@Component
@Primary
@ConditionalOnMissingBean(VdpEngine.class)
public class NoopVdpEngine implements VdpEngine {

    @Override
    public VdpPageResult parsePage(Supplier<InputStream> imageStream, String imageFormat, VdpOptions options) {
        return new VdpPageResult(
                0,
                "",
                VdpPageStatus.DEGRADED,
                Map.of(
                        "contentOrigin", "VDP_TRANSCRIBED",
                        "visualType", "IMAGE",
                        "degraded", true,
                        "engineId", "noop",
                        "interpretiveNote", "[图像解析失败]: VLM visual parsing is disabled"
                )
        );
    }

    @Override
    public String engineId() {
        return "noop";
    }

    @Override
    public String promptVersion() {
        return "disabled";
    }

    @Override
    public EnumSet<VdpMode> supportedModes() {
        return EnumSet.noneOf(VdpMode.class);
    }
}
