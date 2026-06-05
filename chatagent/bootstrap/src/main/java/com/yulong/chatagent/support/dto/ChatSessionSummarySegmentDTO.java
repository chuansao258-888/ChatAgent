package com.yulong.chatagent.support.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO representing one L2 summary segment for a chat session.
 *
 * <p>Each segment covers a stable seq range that was compacted together.
 * Segments are auditable rows; the session-level synopsis is derived from them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionSummarySegmentDTO {
    private String id;
    private String sessionId;
    private Long seqStartNo;
    private Long seqEndNo;
    private Integer turnCount;
    private Integer sourceTokenEstimate;
    private String segmentSummary;
    private String structuredSummaryJson;
    private Map<String, List<String>> anchoredEntities;
    /** Raw JSON string mapped by MyBatis for the {@code anchored_entities} column. */
    private String anchoredEntitiesJson;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
