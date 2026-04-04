package com.yulong.chatagent.rag.parser;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ImageDocumentParserTest {

    @Test
    void shouldEmitFigureSegmentFromVdpMarkdown() {
        AtomicReference<VdpOptions> lastOptions = new AtomicReference<>();
        VdpEngine engine = new VdpEngine() {
            @Override
            public VdpPageResult parsePage(Supplier<InputStream> imageStream, String imageFormat, VdpOptions options) {
                lastOptions.set(options);
                return new VdpPageResult(
                        0,
                        "| Item | Value |\n|---|---|\n| A | 1 |",
                        VdpPageStatus.SUCCESS,
                        Map.of(
                                "contentOrigin", "VDP_TRANSCRIBED",
                                "visualType", "TABLE",
                                "interpretiveNote", "Simple one-row table"
                        )
                );
            }

            @Override
            public EnumSet<VdpMode> supportedModes() {
                return EnumSet.of(VdpMode.PAGE_IMAGE);
            }
        };

        ImageDocumentParser parser = new ImageDocumentParser(engine);

        ParseResult result = parser.parse(
                new byte[]{1, 2, 3},
                "image/png",
                Map.of(
                        "languageHint", "zh",
                        "pipelineSource", PipelineSource.SESSION,
                        "sessionId", "session-1",
                        "documentCacheKey", "should-not-leak",
                        "fileSizeBytes", 123L
                )
        );

        assertThat(result.getParserType()).isEqualTo(ParserType.IMAGE.getType());
        assertThat(result.getExtractionMode()).isEqualTo("VLM_IMAGE");
        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).type()).isEqualTo(SegmentType.FIGURE);
        assertThat(result.getSegments().get(0).metadata())
                .containsEntry("contentOrigin", "VDP_TRANSCRIBED")
                .containsEntry("visualType", "TABLE")
                .containsEntry("interpretiveNote", "Simple one-row table");
        assertThat(result.getFullText()).contains("| Item | Value |");
        assertThat(lastOptions.get().extra())
                .containsEntry("pipelineSource", PipelineSource.SESSION)
                .containsEntry("sessionId", "session-1")
                .doesNotContainKeys("documentCacheKey", "fileSizeBytes");
    }
}
