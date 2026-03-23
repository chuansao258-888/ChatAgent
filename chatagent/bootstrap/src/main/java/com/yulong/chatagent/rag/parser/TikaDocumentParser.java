package com.yulong.chatagent.rag.parser;

import com.yulong.chatagent.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

/**
 * Generic document parser backed by Apache Tika.
 */
@Slf4j
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final Tika TIKA = new Tika();

    @Override
    public String getParserType() {
        return ParserType.TIKA.getType();
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParseResult.ofText("");
        }

        try (ByteArrayInputStream is = new ByteArrayInputStream(content)) {
            String text = TIKA.parseToString(is);
            String cleaned = TextCleanupUtil.cleanup(text);
            return ParseResult.ofText(cleaned);
        } catch (Exception e) {
            log.error("Tika parsing failed: mimeType={}", mimeType, e);
            throw new BizException("Document parsing failed: " + e.getMessage());
        }
    }

    @Override
    public String extractText(InputStream stream, String fileName) {
        try {
            String text = TIKA.parseToString(stream);
            return TextCleanupUtil.cleanup(text);
        } catch (Exception e) {
            log.error("Text extraction failed: fileName={}", fileName, e);
            throw new BizException("Failed to parse file: " + fileName);
        }
    }

    @Override
    public boolean supports(String mimeType) {
        // Markdown is handled by a dedicated parser to preserve the original syntax.
        return mimeType != null && !mimeType.startsWith("text/markdown");
    }
}
