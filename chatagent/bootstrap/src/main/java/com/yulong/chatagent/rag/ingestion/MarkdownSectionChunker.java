package com.yulong.chatagent.rag.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import com.yulong.chatagent.rag.application.MarkdownParserService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MarkdownSectionChunker {

    private final ObjectMapper objectMapper;

    public MarkdownSectionChunker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<KnowledgeChunkDraft> chunk(List<MarkdownParserService.MarkdownSection> sections) {
        List<KnowledgeChunkDraft> drafts = new ArrayList<>();
        for (MarkdownParserService.MarkdownSection section : sections) {
            String title = normalize(section.getTitle());
            if (!StringUtils.hasText(title)) {
                continue;
            }

            String body = normalize(section.getContent());
            String chunkContent = StringUtils.hasText(body)
                    ? title + "\n" + body
                    : title;

            drafts.add(new KnowledgeChunkDraft(
                    chunkContent,
                    buildMetadata(title, body)
            ));
        }
        return drafts;
    }

    private String normalize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.trim();
    }

    private String buildMetadata(String title, String body) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("hasBody", StringUtils.hasText(body));
        metadata.put("contentLength", body.length());
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize markdown chunk metadata", e);
        }
    }
}
