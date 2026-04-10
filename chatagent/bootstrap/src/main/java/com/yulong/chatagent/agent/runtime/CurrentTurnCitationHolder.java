package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.rag.model.CitationMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Run-scoped citation holder keyed by session and turn so citations survive tool execution
 * without being attached to intermediate assistant messages.
 */
@Component
public class CurrentTurnCitationHolder {

    private final ConcurrentMap<String, List<CitationMetadata>> citationsByTurn = new ConcurrentHashMap<>();

    public void put(String sessionId, String turnId, List<CitationMetadata> citations) {
        if (!hasValidKey(sessionId, turnId)) {
            return;
        }
        if (citations == null || citations.isEmpty()) {
            citationsByTurn.remove(key(sessionId, turnId));
            return;
        }
        citationsByTurn.put(key(sessionId, turnId), List.copyOf(new LinkedHashSet<>(citations)));
    }

    public List<CitationMetadata> peek(String sessionId, String turnId) {
        if (!hasValidKey(sessionId, turnId)) {
            return List.of();
        }
        List<CitationMetadata> citations = citationsByTurn.get(key(sessionId, turnId));
        return citations == null ? List.of() : List.copyOf(citations);
    }

    public List<CitationMetadata> take(String sessionId, String turnId) {
        if (!hasValidKey(sessionId, turnId)) {
            return List.of();
        }
        List<CitationMetadata> citations = citationsByTurn.remove(key(sessionId, turnId));
        return citations == null ? List.of() : List.copyOf(citations);
    }

    public void clear(String sessionId, String turnId) {
        if (!hasValidKey(sessionId, turnId)) {
            return;
        }
        citationsByTurn.remove(key(sessionId, turnId));
    }

    /**
     * Removes all citation entries for the given session.
     * Use as a safety net to prevent stale citations from accumulating
     * when individual turn cleanup is missed (e.g. agent run failures).
     */
    public void clearBySession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        String prefix = sessionId.trim() + "::";
        citationsByTurn.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void merge(String sessionId, String turnId, List<CitationMetadata> citations) {
        if (!hasValidKey(sessionId, turnId)) {
            return;
        }
        if (citations == null || citations.isEmpty()) {
            return;
        }
        citationsByTurn.compute(key(sessionId, turnId), (ignored, existing) -> {
            LinkedHashSet<CitationMetadata> merged = new LinkedHashSet<>();
            if (existing != null) {
                merged.addAll(existing);
            }
            merged.addAll(citations);
            return new ArrayList<>(merged);
        });
    }

    private boolean hasValidKey(String sessionId, String turnId) {
        return StringUtils.hasText(sessionId) && StringUtils.hasText(turnId);
    }

    private String key(String sessionId, String turnId) {
        return sessionId.trim() + "::" + turnId.trim();
    }
}
