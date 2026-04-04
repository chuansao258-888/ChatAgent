package com.yulong.chatagent.rag.parser;

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

    private final VdpEngine vdpEngine;

    public ImageDocumentParser(VdpEngine vdpEngine) {
        this.vdpEngine = vdpEngine;
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
        Object pipelineSource = options == null ? null : options.get("pipelineSource");
        if (pipelineSource instanceof PipelineSource source) {
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
        VdpPageResult pageResult = vdpEngine.parsePage(streamSupplier, imageFormatOf(mimeType), vdpOptions);

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
        return mimeType.substring(mimeType.indexOf('/') + 1).trim().toLowerCase();
    }
}
