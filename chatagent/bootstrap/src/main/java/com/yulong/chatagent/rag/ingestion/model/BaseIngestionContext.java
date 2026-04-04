package com.yulong.chatagent.rag.ingestion.model;

import com.yulong.chatagent.rag.parser.ParseResult;
import com.yulong.chatagent.rag.parser.ParseSegment;
import com.yulong.chatagent.rag.parser.SegmentType;
import lombok.Data;
import lombok.experimental.SuperBuilder;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Shared ingestion context carrying parser/enhancer output through the segment-native ingestion
 * pipeline.
 */
@Data
@SuperBuilder
public abstract class BaseIngestionContext {

    private String fileExtension;

    private List<ParseSegment> segments;
    private List<ParseSegment> enhancedSegments;
    private ParseResult parseResult;
    private List<KnowledgeChunkDraft> chunkDrafts;

    /**
     * Chunking entry point for the segment-native pipeline:
     * 1. prefer enhancer-produced segments when available
     * 2. otherwise consume parser segments
     */
    public List<ParseSegment> resolveChunkSegments() {
        if (enhancedSegments != null && !enhancedSegments.isEmpty()) {
            return enhancedSegments;
        }
        if (segments != null && !segments.isEmpty()) {
            return segments;
        }
        return List.of();
    }

    /**
     * Produces a bounded document prefix for downstream components that need broad context while
     * avoiding unbounded string concatenation.
     */
    public String resolveDocumentPrefix(int maxChars) {
        if (maxChars <= 0) {
            return "";
        }
        List<ParseSegment> segs = resolveChunkSegments();
        if (segs == null || segs.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(Math.min(maxChars + 256, 65_536));
        for (ParseSegment seg : segs) {
            if (!StringUtils.hasText(seg.text())) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            int remaining = maxChars - builder.length();
            if (remaining <= 0) {
                break;
            }
            if (seg.text().length() <= remaining) {
                builder.append(seg.text());
            } else {
                builder.append(seg.text(), 0, remaining);
                break;
            }
        }
        return builder.toString();
    }
}
