package com.yulong.chatagent.intent.application;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Temporary clarification state stored per session in Redis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingIntentResolution {
    private String sessionId;
    private List<String> candidateNodeIds;
    private String originalQuery;
    private String parentPath;
    private Instant expiresAt;
}

