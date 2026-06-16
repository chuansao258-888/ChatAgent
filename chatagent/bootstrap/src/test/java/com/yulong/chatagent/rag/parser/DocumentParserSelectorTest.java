package com.yulong.chatagent.rag.parser;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentParserSelectorTest {

    @Test
    void shouldPreferImageParserOverGenericTikaParser() {
        FileTypeDetector detector = mock(FileTypeDetector.class);
        DetectedFileType detectedFileType = DetectedFileType.accepted("png", "image/png");
        when(detector.detect(any(byte[].class), org.mockito.ArgumentMatchers.eq("scan.png"),
                org.mockito.ArgumentMatchers.eq("image/png"), org.mockito.ArgumentMatchers.eq(PipelineSource.SESSION)))
                .thenReturn(detectedFileType);

        DocumentParser tikaParser = new StubParser(ParserType.TIKA.getType(), true);
        DocumentParser imageParser = new StubParser(ParserType.IMAGE.getType(), true);
        DocumentParserSelector selector = new DocumentParserSelector(List.of(tikaParser, imageParser), detector);

        DocumentParser selected = selector.selectParser(new byte[]{1}, "scan.png", "image/png", PipelineSource.SESSION);

        assertThat(selected.getParserType()).isEqualTo(ParserType.IMAGE.getType());
    }

    @Test
    void shouldPreferHtmlParserOverTikaForHtmlExtension() {
        FileTypeDetector detector = mock(FileTypeDetector.class);
        when(detector.detect(any(byte[].class),
                org.mockito.ArgumentMatchers.eq("page.html"),
                org.mockito.ArgumentMatchers.eq("text/html"),
                org.mockito.ArgumentMatchers.eq(PipelineSource.KNOWLEDGE)))
                .thenReturn(DetectedFileType.accepted("html", "text/html"));

        DocumentParser tikaParser = new StubParser(ParserType.TIKA.getType(), true);
        DocumentParser htmlParser = new StubParser(ParserType.HTML.getType(), true);
        DocumentParserSelector selector = new DocumentParserSelector(List.of(tikaParser, htmlParser), detector);

        DocumentParser selected = selector.selectParser(new byte[]{1}, "page.html", "text/html", PipelineSource.KNOWLEDGE);
        assertThat(selected.getParserType()).isEqualTo(ParserType.HTML.getType());
    }

    @Test
    void shouldPreferHtmlParserOverTikaForHtmExtension() {
        FileTypeDetector detector = mock(FileTypeDetector.class);
        when(detector.detect(any(byte[].class),
                org.mockito.ArgumentMatchers.eq("page.htm"),
                org.mockito.ArgumentMatchers.eq("text/html"),
                org.mockito.ArgumentMatchers.eq(PipelineSource.SESSION)))
                .thenReturn(DetectedFileType.accepted("htm", "text/html"));

        DocumentParser tikaParser = new StubParser(ParserType.TIKA.getType(), true);
        DocumentParser htmlParser = new StubParser(ParserType.HTML.getType(), true);
        DocumentParserSelector selector = new DocumentParserSelector(List.of(tikaParser, htmlParser), detector);

        DocumentParser selected = selector.selectParser(new byte[]{1}, "page.htm", "text/html", PipelineSource.SESSION);
        assertThat(selected.getParserType()).isEqualTo(ParserType.HTML.getType());
    }

    @Test
    void shouldPreferHtmlParserOverTikaForMimeOnly() {
        FileTypeDetector detector = mock(FileTypeDetector.class);
        when(detector.detect(any(byte[].class),
                org.mockito.ArgumentMatchers.eq("upload"),
                org.mockito.ArgumentMatchers.eq("text/html"),
                org.mockito.ArgumentMatchers.eq(PipelineSource.KNOWLEDGE)))
                .thenReturn(DetectedFileType.accepted(null, "text/html"));

        DocumentParser tikaParser = new StubParser(ParserType.TIKA.getType(), true);
        DocumentParser htmlParser = new StubParser(ParserType.HTML.getType(), true);
        DocumentParserSelector selector = new DocumentParserSelector(List.of(tikaParser, htmlParser), detector);

        DocumentParser selected = selector.selectParser(new byte[]{1}, "upload", "text/html", PipelineSource.KNOWLEDGE);
        assertThat(selected.getParserType()).isEqualTo(ParserType.HTML.getType());
    }

    @Test
    void shouldStillSelectTikaForNonHtmlType() {
        FileTypeDetector detector = mock(FileTypeDetector.class);
        when(detector.detect(any(byte[].class),
                org.mockito.ArgumentMatchers.eq("document.txt"),
                org.mockito.ArgumentMatchers.eq("text/plain"),
                org.mockito.ArgumentMatchers.eq(PipelineSource.KNOWLEDGE)))
                .thenReturn(DetectedFileType.accepted("txt", "text/plain"));

        DocumentParser tikaParser = new StubParser(ParserType.TIKA.getType(), true);
        DocumentParser htmlParser = new HtmlAwareStubParser();
        DocumentParserSelector selector = new DocumentParserSelector(List.of(tikaParser, htmlParser), detector);

        DocumentParser selected = selector.selectParser(new byte[]{1}, "document.txt", "text/plain", PipelineSource.KNOWLEDGE);
        assertThat(selected.getParserType()).isEqualTo(ParserType.TIKA.getType());
    }

    private record StubParser(String parserType, boolean supported) implements DocumentParser {

        @Override
        public String getParserType() {
            return parserType;
        }

        @Override
        public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
            return ParseResult.ofText("");
        }

        @Override
        public ParseResult parse(Supplier<InputStream> streamSupplier, String mimeType, Map<String, Object> options) {
            return ParseResult.ofText("");
        }

        @Override
        public boolean supports(DetectedFileType type) {
            return supported;
        }
    }

    /** Stub that only supports HTML types, matching real HtmlDocumentParser.supports() behavior. */
    private static class HtmlAwareStubParser implements DocumentParser {
        @Override public String getParserType() { return ParserType.HTML.getType(); }
        @Override public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) { return ParseResult.ofText(""); }
        @Override public ParseResult parse(Supplier<InputStream> streamSupplier, String mimeType, Map<String, Object> options) { return ParseResult.ofText(""); }
        @Override public boolean supports(DetectedFileType type) { return type != null && !type.rejected() && type.isHtml(); }
    }
}
