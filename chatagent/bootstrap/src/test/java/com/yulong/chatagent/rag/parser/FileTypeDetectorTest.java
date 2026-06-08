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

    // ── HTML and CSV support ────────────────────────────────────────────

    @Test
    void shouldAcceptHtmlExtension() {
        DetectedFileType detected = detector.detect(
                "<html><body>Hello</body></html>".getBytes(),
                "page.html",
                "text/html",
                PipelineSource.KNOWLEDGE
        );

        assertThat(detected.rejected()).isFalse();
        assertThat(detected.mimeType()).isEqualTo("text/html");
    }

    @Test
    void shouldAcceptHtmExtension() {
        DetectedFileType detected = detector.detect(
                "<html><body>Hello</body></html>".getBytes(),
                "page.htm",
                "text/html",
                PipelineSource.KNOWLEDGE
        );

        assertThat(detected.rejected()).isFalse();
        assertThat(detected.mimeType()).isEqualTo("text/html");
    }

    @Test
    void shouldAcceptCsvExtension() {
        DetectedFileType detected = detector.detect(
                "header1,header2\nvalue1,value2".getBytes(),
                "data.csv",
                "text/csv",
                PipelineSource.KNOWLEDGE
        );

        assertThat(detected.rejected()).isFalse();
        assertThat(detected.mimeType()).isEqualTo("text/csv");
    }

    @Test
    void shouldAcceptCsvWithTextPlainMime() {
        DetectedFileType detected = detector.detect(
                "a,b\n1,2".getBytes(),
                "data.csv",
                "text/plain",
                PipelineSource.KNOWLEDGE
        );

        assertThat(detected.rejected()).isFalse();
        assertThat(detected.mimeType()).isEqualTo("text/csv");
    }

    @Test
    void shouldAcceptHtmlMimeWithoutMatchingExtension() {
        DetectedFileType detected = detector.detect(
                "<html><body>Hi</body></html>".getBytes(),
                "unknown_file",
                "text/html",
                PipelineSource.KNOWLEDGE
        );

        assertThat(detected.rejected()).isFalse();
    }

    /**
     * Regression: every MIME type and extension used in the Phase 10a
     * doc-ingestion SMOKE_CATALOG must be accepted by the detector.
     */
    @Test
    void shouldAcceptAllDocIngestionCatalogFormats() {
        String[][] catalogEntries = {
                {"txt", "text/plain"},
                {"pdf", "application/pdf"},
                {"html", "text/html"},
                {"md", "text/markdown"},
                {"xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
        };
        for (String[] entry : catalogEntries) {
            byte[] prefix = switch (entry[0]) {
                case "pdf" -> "%PDF-1.4 test content".getBytes();
                case "html" -> "<html><body>test</body></html>".getBytes();
                case "xlsx" -> zipPrefix(); // XLSX is ZIP-based
                case "md" -> "# Title\n\nParagraph text".getBytes();
                default -> "plain text content".getBytes();
            };
            DetectedFileType detected = detector.detect(
                    prefix,
                    "test." + entry[0],
                    entry[1],
                    PipelineSource.KNOWLEDGE
            );
            assertThat(detected.rejected())
                    .as("Phase 10a catalog format %s/%s should be accepted", entry[0], entry[1])
                    .isFalse();
        }
    }
}
