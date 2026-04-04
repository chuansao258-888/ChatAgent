package com.yulong.chatagent.rag.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileTypeDetectorTest {

    private final FileTypeDetector detector = new FileTypeDetector();

    @Test
    void shouldRejectStandaloneImagesForKnowledgePipeline() {
        DetectedFileType detected = detector.detect(
                pngPrefix(),
                "scan.png",
                "image/png",
                PipelineSource.KNOWLEDGE
        );

        assertThat(detected.rejected()).isTrue();
        assertThat(detected.rejectionReason()).contains("standalone images");
    }

    @Test
    void shouldAcceptStandaloneImagesForSessionPipeline() {
        DetectedFileType detected = detector.detect(
                pngPrefix(),
                "scan.png",
                "image/png",
                PipelineSource.SESSION
        );

        assertThat(detected.rejected()).isFalse();
        assertThat(detected.isImage()).isTrue();
        assertThat(detected.mimeType()).isEqualTo("image/png");
    }

    private byte[] pngPrefix() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    }
}
