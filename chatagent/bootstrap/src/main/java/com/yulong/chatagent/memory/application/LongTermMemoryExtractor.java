package com.yulong.chatagent.memory.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.conversation.summary.AtomicConversationTurn;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts long-term memory candidates from raw conversation turns using an LLM.
 *
 * <p>Follows the same ChatModelRouter + PromptLoader pattern as {@code IncrementalSummarizer}.
 * The extractor calls the configured model with a strict JSON-output prompt,
 * parses the response, validates types and tags, and returns an {@link ExtractionResult}.
 *
 * <p>Failures (malformed JSON, empty model output, model exceptions) return
 * {@link ExtractionResult#failure()} — callers decide whether to retry or log.
 */
@Component
@Slf4j
public class LongTermMemoryExtractor {

    private static final Set<String> VALID_TYPES = Set.of("preference", "fact");
    private static final TypeReference<List<JsonNode>> MEMORY_LIST_TYPE = new TypeReference<>() {};

    private final PromptLoader promptLoader;
    private final ChatModelRouter chatModelRouter;
    private final ObjectMapper objectMapper;
    private final String extractorModel;

    public LongTermMemoryExtractor(PromptLoader promptLoader,
                                   ChatModelRouter chatModelRouter,
                                   ObjectMapper objectMapper,
                                   @Value("${chatagent.memory.l3.extractor-model:deepseek-chat}") String extractorModel) {
        this.promptLoader = promptLoader;
        this.chatModelRouter = chatModelRouter;
        this.objectMapper = objectMapper;
        this.extractorModel = extractorModel;
    }

    /**
     * Extracts long-term memory candidates from the given conversation turns.
     *
     * @param turns raw turns from the L2 compression batch
     * @return extraction result with valid memories, or failure if the LLM call failed
     */
    public ExtractionResult extract(List<AtomicConversationTurn> turns) {
        String prompt = buildPrompt(turns);
        try {
            ChatClient chatClient = chatModelRouter.route(extractorModel);
            String content = chatClient.prompt(prompt)
                    .call()
                    .content();
            if (!StringUtils.hasText(content)) {
                log.debug("L3 extractor returned blank content");
                return ExtractionResult.failure();
            }
            return parseResponse(content.trim());
        } catch (Exception e) {
            log.warn("L3 extraction failed: error={}", e.getMessage());
            return ExtractionResult.failure();
        }
    }

    String buildPrompt(List<AtomicConversationTurn> turns) {
        return promptLoader.render(PromptConstants.L3_MEMORY_EXTRACTOR, Map.of(
                "formattedTurns", formatTurns(turns)
        ));
    }

    ExtractionResult parseResponse(String response) {
        String json = stripCodeFence(response);
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode memoriesNode = root.get("memories");
            if (memoriesNode == null || !memoriesNode.isArray()) {
                log.debug("L3 extractor response missing 'memories' array");
                return ExtractionResult.failure();
            }
            List<ExtractedMemory> valid = new ArrayList<>();
            for (JsonNode node : memoriesNode) {
                ExtractedMemory memory = parseMemory(node);
                if (memory != null) {
                    valid.add(memory);
                }
            }
            return ExtractionResult.success(valid);
        } catch (Exception e) {
            log.warn("L3 extractor JSON parse failed: error={}", e.getMessage());
            return ExtractionResult.failure();
        }
    }

    private ExtractedMemory parseMemory(JsonNode node) {
        if (node == null) {
            return null;
        }
        String type = textField(node, "type");
        String content = textField(node, "content");
        if (!VALID_TYPES.contains(type)) {
            log.debug("L3 extractor dropped memory with invalid type: type={}", type);
            return null;
        }
        if (!StringUtils.hasText(content)) {
            log.debug("L3 extractor dropped memory with empty content");
            return null;
        }
        List<String> tags = parseTags(node.get("tags"));
        return new ExtractedMemory(type, content.trim(), tags);
    }

    private List<String> parseTags(JsonNode tagsNode) {
        if (tagsNode == null || !tagsNode.isArray()) {
            return List.of();
        }
        Set<String> unique = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (JsonNode tagNode : tagsNode) {
            if (tagNode.isTextual()) {
                String tag = normalizeTag(tagNode.asText());
                if (!tag.isEmpty() && unique.add(tag)) {
                    result.add(tag);
                }
            }
        }
        return result;
    }

    private String normalizeTag(String tag) {
        if (tag == null) {
            return "";
        }
        // Lowercase, trim, collapse whitespace, replace spaces/special chars with hyphens.
        String normalized = tag.trim().toLowerCase().replaceAll("[\\s_]+", "-").replaceAll("[^a-z0-9\\u4e00-\\u9fff-]", "");
        // Collapse multiple hyphens and strip leading/trailing hyphens.
        return normalized.replaceAll("-+", "-").replaceAll("^-|-$", "");
    }

    private static String textField(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull() && child.isTextual()) ? child.asText() : null;
    }

    static String stripCodeFence(String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z0-9_-]*\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed.trim();
    }

    private String formatTurns(List<AtomicConversationTurn> turns) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < turns.size(); i++) {
            AtomicConversationTurn turn = turns.get(i);
            builder.append("Turn ").append(i + 1).append(":\n");
            if (turn.userMessages() != null) {
                for (String userMessage : turn.userMessages()) {
                    builder.append("- User: ").append(userMessage).append('\n');
                }
            }
            if (StringUtils.hasText(turn.assistantConclusion())) {
                builder.append("- Assistant: ").append(turn.assistantConclusion()).append('\n');
            }
        }
        return builder.toString().trim();
    }
}
