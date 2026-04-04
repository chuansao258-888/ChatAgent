package com.yulong.chatagent.rag.parser;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Parser for markdown uploads that preserves the original markdown content.
 */
@Component
public class MarkdownDocumentParser implements DocumentParser {

    @Override
    public String getParserType() {
        return ParserType.MARKDOWN.getType();
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParseResult.ofText("");
        }

        String text = new String(content, StandardCharsets.UTF_8);
        return ParseResult.builder()
                .segments(List.of(new ParseSegment(text, 0, SegmentType.FULL, Map.of())))
                .parserType(ParserType.MARKDOWN.getType())
                .build();
    }

    @Override
    public ParseResult parse(Supplier<InputStream> streamSupplier, String mimeType, Map<String, Object> options) {
        try (InputStream stream = streamSupplier.get()) {
            if (stream == null) {
                return ParseResult.ofText("");
            }
            String text = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
            return ParseResult.builder()
                    .segments(List.of(new ParseSegment(text, 0, SegmentType.FULL, Map.of())))
                    .parserType(ParserType.MARKDOWN.getType())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse markdown stream", e);
        }
    }

    @Override
    public String extractText(InputStream stream, String fileName) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse markdown file: " + fileName, e);
        }
    }

    @Override
    public boolean supports(DetectedFileType type) {
        return type != null && type.isMarkdown();
    }
}
