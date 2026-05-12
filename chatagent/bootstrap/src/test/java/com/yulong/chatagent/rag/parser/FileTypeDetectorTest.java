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

    @Test
    void shouldAcceptPptxExtension() {
        DetectedFileType detected = detector.detect(
                zipPrefix(),
                "presentation.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                PipelineSource.KNOWLEDGE
        );

        assertThat(detected.rejected()).isFalse();
        assertThat(detected.isPowerPoint()).isTrue();
    }

    @Test
    void shouldAcceptXlsxExtension() {
        DetectedFileType detected = detector.detect(
                zipPrefix(),
                "data.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                PipelineSource.KNOWLEDGE
        );

        assertThat(detected.rejected()).isFalse();
        assertThat(detected.isSpreadsheet()).isTrue();
    }

    @Test
    void shouldAcceptPptxWithZipMime() {
        DetectedFileType detected = detector.detect(
                zipPrefix(),
                "slides.pptx",
                "application/zip",
                PipelineSource.KNOWLEDGE
        );

        assertThat(detected.rejected()).isFalse();
        assertThat(detected.extension()).isEqualTo("pptx");
    }

    @Test
    void shouldAcceptXlsxWithOctetStreamMime() {
        DetectedFileType detected = detector.detect(
                zipPrefix(),
                "report.xlsx",
                "application/octet-stream",
                PipelineSource.KNOWLEDGE
        );

        assertThat(detected.rejected()).isFalse();
    }

    @Test
    void shouldRejectLegacyPpt() {
        DetectedFileType detected = detector.detect(
                new byte[8],
                "old.ppt",
                "application/vnd.ms-powerpoint",
                PipelineSource.KNOWLEDGE
        );

        assertThat(detected.rejected()).isTrue();
        assertThat(detected.rejectionReason()).contains("Unsupported file extension");
    }

    @Test
    void shouldRejectLegacyXls() {
        DetectedFileType detected = detector.detect(
                new byte[8],
                "old.xls",
                "application/vnd.ms-excel",
                PipelineSource.KNOWLEDGE
        );

        assertThat(detected.rejected()).isTrue();
        assertThat(detected.rejectionReason()).contains("Unsupported file extension");
    }

    @Test
    void shouldRejectPlainZip() {
        DetectedFileType detected = detector.detect(
                zipPrefix(),
                "archive.zip",
                "application/zip",
                PipelineSource.KNOWLEDGE
        );

        assertThat(detected.rejected()).isTrue();
    }

    private byte[] zipPrefix() {
        return new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00};
    }

    private byte[] pngPrefix() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    }
}
