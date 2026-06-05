package com.yulong.chatagent.conversation.summary;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses LLM JSON output into a {@link StructuredSummary}.
 *
 * <p>If the JSON is missing, blank, or structurally invalid, falls back to a
 * deterministic summary built from the raw turns. This keeps the compaction
 * pipeline running even when the model returns unexpected output.
 */
public class StructuredSummaryParser {

    private static final Logger log = LoggerFactory.getLogger(StructuredSummaryParser.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private StructuredSummaryParser() {
    }

    /**
     * Parses a raw JSON string into a StructuredSummary.
     *
     * @param rawJson the model's JSON response
     * @return parsed summary, or deterministic fallback if parsing fails
     */
    public static StructuredSummary parse(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return StructuredSummary.empty();
        }

        String trimmed = rawJson.trim();
        // Strip markdown code fences if present
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            trimmed = trimmed.trim();
        }

        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(trimmed, MAP_TYPE);
            return new StructuredSummary(
                    extractString(map, "summary"),
                    extractStringList(map, "facts"),
                    extractStringList(map, "decisions"),
                    extractStringList(map, "open_tasks"),
                    extractEntities(map)
            );
        } catch (Exception e) {
            log.warn("Failed to parse structured summary JSON: errorClass={}, inputChars={}",
                    e.getClass().getSimpleName(), trimmed.length());
            // Return empty so the caller falls through to deterministic fallback from raw turns.
            return StructuredSummary.empty();
        }
    }

    /**
     * Creates a deterministic fallback summary from raw turns when the model
     * call fails or returns unusable output.
     */
    public static StructuredSummary fallback(List<AtomicConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return StructuredSummary.empty();
        }

        StringBuilder summary = new StringBuilder();
        List<String> facts = new ArrayList<>();

        for (int i = 0; i < turns.size(); i++) {
            AtomicConversationTurn turn = turns.get(i);
            if (turn.userMessages() != null) {
                for (String userMessage : turn.userMessages()) {
                    summary.append("User: ").append(userMessage).append('\n');
                    facts.add("User asked: " + userMessage);
                }
            }
            if (StringUtils.hasText(turn.assistantConclusion())) {
                summary.append("Assistant: ").append(turn.assistantConclusion()).append('\n');
            }
        }

        return new StructuredSummary(
                summary.toString().trim(),
                List.copyOf(facts),
                List.of(),
                List.of(),
                Map.of()
        );
    }

    private static String extractString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String s ? s.trim() : "";
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractStringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && StringUtils.hasText(s)) {
                result.add(s.trim());
            }
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> extractEntities(Map<String, Object> map) {
        Object value = map.get("entities");
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                continue;
            }
            if (entry.getValue() instanceof List<?> list) {
                List<String> items = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof String s && StringUtils.hasText(s)) {
                        items.add(s.trim());
                    }
                }
                if (!items.isEmpty()) {
                    result.put(key, List.copyOf(items));
                }
            }
        }
        return Map.copyOf(result);
    }
}
