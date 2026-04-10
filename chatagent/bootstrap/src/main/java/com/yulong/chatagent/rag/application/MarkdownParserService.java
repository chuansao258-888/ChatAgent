package com.yulong.chatagent.rag.application;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

import java.io.InputStream;
import java.util.List;

/**
 * Parses markdown files into titled sections that can later be chunked for retrieval.
 */
public interface MarkdownParserService {

    /**
     * Extracts logical sections from a markdown input stream.
     *
     * @param inputStream markdown document stream
     * @return ordered section list with titles and corresponding content
     */
    List<MarkdownSection> parseMarkdown(InputStream inputStream);

    /**
     * Simple value object representing one markdown heading and its content block.
     */
    @Data
    @AllArgsConstructor
    @ToString
    class MarkdownSection {
        private String title;
        private String content;
    }
}
