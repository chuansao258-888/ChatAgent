package com.yulong.chatagent.rag.parser;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Parser for standalone image uploads routed through the lightweight VDP engine.
 */
@Component
public class ImageDocumentParser implements DocumentParser {

    private final VdpEngineRouter engineRouter;

    @Autowired
    public ImageDocumentParser(VdpEngineRouter engineRouter) {
        this.engineRouter = engineRouter;
    }

    ImageDocumentParser(VdpEngine vdpEngine) {
        this(VdpEngineRouter.forTesting(vdpEngine));
    }

    @Override
    public String getParserType() {
        return ParserType.IMAGE.getType();
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        byte[] bytes = content == null ? new byte[0] : content;
        return parse(() -> new ByteArrayInputStream(bytes), mimeType, options);
    }

    @Override
    public ParseResult parse(Supplier<InputStream> streamSupplier, String mimeType, Map<String, Object> options) {
        Map<String, Object> extra = new LinkedHashMap<>();
        Object pipelineSourceValue = options == null ? null : options.get("pipelineSource");
        if (pipelineSourceValue instanceof PipelineSource source) {
            extra.put("pipelineSource", source);
        }
        String sessionId = stringOption(options, "sessionId");
        if (StringUtils.hasText(sessionId)) {
            extra.put("sessionId", sessionId.trim());
        }
        VdpOptions vdpOptions = new VdpOptions(
                booleanOption(options, "recognizeFormulas"),
                stringOption(options, "languageHint"),
                extra
        );
        PipelineSource pipelineSource = pipelineSourceValue instanceof PipelineSource source
                ? source
                : PipelineSource.KNOWLEDGE;
        VdpEngine engine = engineRouter.resolveForPageImage(pipelineSource);
        VdpPageResult pageResult;
        try {
            pageResult = engine.parsePage(streamSupplier, imageFormatOf(mimeType), vdpOptions);
            if (pageResult == null) {
                pageResult = degradedPageResult(engine, "VDP engine returned null");
            }
        } catch (Exception e) {
            pageResult = degradedPageResult(engine, e.getMessage());
        }

        Map<String, Object> metadata = new LinkedHashMap<>(pageResult.metadata());
        metadata.putIfAbsent("contentOrigin", "VDP_TRANSCRIBED");
        metadata.putIfAbsent("visualType", "IMAGE");
        metadata.putIfAbsent("degraded", pageResult.status() == VdpPageStatus.DEGRADED);
        metadata.put("mimeType", mimeType == null ? "application/octet-stream" : mimeType);

        List<String> warnings = new ArrayList<>();
        if (pageResult.status() == VdpPageStatus.DEGRADED) {
            warnings.add("Image parsing degraded to non-structured markdown output");
        }

        QualityLevel qualityLevel = pageResult.status() == VdpPageStatus.SUCCESS
                ? QualityLevel.HIGH
                : QualityLevel.MEDIUM;
        return ParseResult.builder()
                .segments(List.of(new ParseSegment(pageResult.markdown(), 0, SegmentType.FIGURE, metadata)))
                .parserType(ParserType.IMAGE.getType())
                .extractionMode("VLM_IMAGE")
                .qualityLevel(qualityLevel)
                .warnings(warnings)
                .metadata(Map.of("contentOrigin", metadata.get("contentOrigin")))
                .build();
    }

    @Override
    public boolean supports(DetectedFileType type) {
        return type != null && type.isImage();
    }

    private boolean booleanOption(Map<String, Object> options, String key) {
        Object value = options == null ? null : options.get(key);
        return value instanceof Boolean bool && bool;
    }

    private String stringOption(Map<String, Object> options, String key) {
        Object value = options == null ? null : options.get(key);
        return value == null ? null : value.toString();
    }

    private String imageFormatOf(String mimeType) {
        if (!StringUtils.hasText(mimeType) || !mimeType.contains("/")) {
            return "png";
        }
        String subtype = mimeType.substring(mimeType.indexOf('/') + 1);
        return subtype.split("[+;]", 2)[0].trim().toLowerCase();
    }

    private VdpPageResult degradedPageResult(VdpEngine engine, String reason) {
        String normalizedReason = StringUtils.hasText(reason) ? reason.trim() : "Unknown image parsing failure";
        return new VdpPageResult(
                0,
                "",
                VdpPageStatus.DEGRADED,
                Map.of(
                        "contentOrigin", "VDP_TRANSCRIBED",
                        "visualType", "IMAGE",
                        "degraded", true,
                        "engineId", engine == null ? "unknown" : engine.engineId(),
                        "promptVersion", engine == null ? "default" : engine.promptVersion(),
                        "interpretiveNote", "[图像解析失败]: " + normalizedReason
                )
        );
    }
}
