package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.conversation.port.ChatSessionSummarySegmentRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import com.yulong.chatagent.support.dto.ChatSessionSummarySegmentDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Resolves the L2 historical context summary for runtime prompt injection.
 *
 * <p>V2 behavior: returns synopsis plus the latest N segment summaries (bounded by
 * {@code runtime-max-segments}). Returns empty string when neither synopsis nor
 * active nonblank segments exist, so the caller omits {@code [Historical Context Summary]}.
 */
@Component
public class AgentSessionSummaryResolver {

    private final ChatSessionSummaryRepository chatSessionSummaryRepository;
    private final ChatSessionSummarySegmentRepository segmentRepository;
    private final int runtimeMaxSegments;

    public AgentSessionSummaryResolver(ChatSessionSummaryRepository chatSessionSummaryRepository,
                                       ChatSessionSummarySegmentRepository segmentRepository,
                                       @Value("${chatagent.memory.compaction.v2.runtime-max-segments:3}") int runtimeMaxSegments) {
        this.chatSessionSummaryRepository = chatSessionSummaryRepository;
        this.segmentRepository = segmentRepository;
        this.runtimeMaxSegments = Math.max(runtimeMaxSegments, 0);
    }

    /**
     * Resolves the V2 synopsis + latest segment summaries for the given session.
     *
     * <p>The output format is: synopsis text followed by up to N latest nonblank
     * segment summaries, each prefixed with its seq range for traceability.
     * Blank segment summaries are skipped before applying the N limit.
     *
     * @return formatted summary text, or empty string if neither synopsis nor
     *         active nonblank segments exist
     */
    public String resolve(String chatSessionId) {
        if (!StringUtils.hasText(chatSessionId)) {
            return "";
        }
        ChatSessionSummaryDTO summary = chatSessionSummaryRepository.findBySessionId(chatSessionId);
        String synopsis = (summary != null && StringUtils.hasText(summary.getSynopsis()))
                ? summary.getSynopsis().trim() : null;

        // Collect up to N nonblank segment summaries, newest first.
        List<String> segmentParts = List.of();
        if (runtimeMaxSegments > 0) {
            List<ChatSessionSummarySegmentDTO> segments =
                    segmentRepository.findActiveBySessionId(chatSessionId);
            segmentParts = new java.util.ArrayList<>();
            for (ChatSessionSummarySegmentDTO seg : segments) {
                if (segmentParts.size() >= runtimeMaxSegments) {
                    break;
                }
                if (StringUtils.hasText(seg.getSegmentSummary())) {
                    segmentParts.add("[Segment " + seg.getSeqStartNo()
                            + ".." + seg.getSeqEndNo() + "] " + seg.getSegmentSummary().trim());
                }
            }
        }

        // Return empty when neither synopsis nor segments have content.
        if (synopsis == null && segmentParts.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        if (synopsis != null) {
            result.append(synopsis);
        }
        for (String part : segmentParts) {
            if (result.length() > 0) {
                result.append("\n\n");
            }
            result.append(part);
        }
        return result.toString();
    }
}
