package com.yulong.chatagent.rag.service;

import com.yulong.chatagent.rag.model.CitationMetadata;
import com.yulong.chatagent.rag.model.RetrievalHit;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders structured retrieval hits into a stable prompt-friendly text block.
 */
@Component
public class RetrievalHitFormatter {

    public String formatForPrompt(List<RetrievalHit> hits) {
        return formatWithCitations(hits).promptText();
    }

    public FormattedRetrievalPrompt formatWithCitations(List<RetrievalHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return new FormattedRetrievalPrompt(
                    "No relevant attached session-file content found.",
                    List.of()
            );
        }

        List<String> sections = new ArrayList<>(hits.size());
        List<CitationMetadata> citations = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            RetrievalHit hit = hits.get(i);
            int citationNumber = i + 1;
            citations.add(toCitationMetadata(hit));
            if (shouldIncludeInPrompt(hit)) {
                sections.add(formatSingleHit(hit, citationNumber));
            }
        }
        if (sections.isEmpty()) {
            return new FormattedRetrievalPrompt(
                    "No relevant attached session-file content found.",
                    List.copyOf(citations)
            );
        }
        String prompt = """
                Use the following numbered evidence snippets when answering.
                If you rely on a snippet, cite it inline with [n] using the matching number below.

                %s
                """.formatted(String.join("\n\n---\n\n", sections)).trim();
        return new FormattedRetrievalPrompt(prompt, List.copyOf(citations));
    }

    private boolean shouldIncludeInPrompt(RetrievalHit hit) {
        return hit != null && !"filtered".equals(hit.scoreType());
    }

    private String formatSingleHit(RetrievalHit hit, int citationNumber) {
        List<String> lines = new ArrayList<>();
        lines.add("[" + citationNumber + "] Source: " + buildSourceLabel(hit));
        if (StringUtils.hasText(hit.sectionPath())) {
            lines.add("Section: " + hit.sectionPath());
        }
        if (StringUtils.hasText(hit.contextText())) {
            lines.add("Chunk Context:\n" + hit.contextText());
        }
        if (StringUtils.hasText(hit.content())) {
            lines.add("Chunk Content:\n" + hit.content());
        } else {
            lines.add("Chunk Content:\n");
        }
        return String.join("\n", lines);
    }

    private CitationMetadata toCitationMetadata(RetrievalHit hit) {
        return new CitationMetadata(
                hit.sourceType(),
                hit.sourceId(),
                hit.documentId(),
                hit.documentName(),
                hit.sectionPath(),
                hit.chunkIndex(),
                buildSnippet(hit),
                hit.score(),
                hit.scoreType(),
                hit.isFallback()
        );
    }

    private String buildSnippet(RetrievalHit hit) {
        String snippet = StringUtils.hasText(hit.content()) ? hit.content() : hit.contextText();
        if (!StringUtils.hasText(snippet)) {
            return "";
        }
        String normalized = snippet.replaceAll("\\s+", " ").trim();
        int maxChars = 180;
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars - 3).trim() + "...";
    }

    private String buildSourceLabel(RetrievalHit hit) {
        StringBuilder builder = new StringBuilder();
        String documentName = StringUtils.hasText(hit.documentName()) ? hit.documentName() : hit.documentId();
        if (StringUtils.hasText(documentName)) {
            builder.append(documentName);
        } else {
            builder.append("Unknown");
        }
        builder.append(" [").append(hit.sourceType()).append(']');
        if (hit.chunkIndex() != null) {
            builder.append(" chunk ").append(hit.chunkIndex());
        }
        return builder.toString();
    }
}
