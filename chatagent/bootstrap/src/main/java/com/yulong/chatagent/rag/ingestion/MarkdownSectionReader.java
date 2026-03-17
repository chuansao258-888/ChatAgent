package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.rag.service.DocumentStorageService;
import com.yulong.chatagent.rag.service.MarkdownParserService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class MarkdownSectionReader {

    private final DocumentStorageService documentStorageService;
    private final MarkdownParserService markdownParserService;

    public MarkdownSectionReader(DocumentStorageService documentStorageService,
                                 MarkdownParserService markdownParserService) {
        this.documentStorageService = documentStorageService;
        this.markdownParserService = markdownParserService;
    }

    public List<MarkdownParserService.MarkdownSection> read(String filePath) throws IOException {
        Path path = documentStorageService.getFilePath(filePath);
        try (InputStream inputStream = Files.newInputStream(path)) {
            return markdownParserService.parseMarkdown(inputStream);
        }
    }
}
