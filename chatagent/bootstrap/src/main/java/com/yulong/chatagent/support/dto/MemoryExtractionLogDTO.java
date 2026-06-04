package com.yulong.chatagent.support.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO representing one memory extraction log entry for L3 idempotency tracking.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryExtractionLogDTO {
    private String id;
    private String userId;
    private String sessionId;
    private Long seqStartNo;
    private Long seqEndNo;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
