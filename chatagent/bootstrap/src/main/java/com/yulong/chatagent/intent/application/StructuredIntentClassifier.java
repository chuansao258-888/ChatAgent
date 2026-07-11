package com.yulong.chatagent.intent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.ChatModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executes and strictly validates the bounded structured intent-classifier call. */
@Component
public class StructuredIntentClassifier {

    public static final String PROMPT_VERSION = "v1";
    public static final double TEMPERATURE = 0.0d;
    public static final int MAX_TOKENS = 384;

    private static final Logger log = LoggerFactory.getLogger(StructuredIntentClassifier.class);
    private static final Set<String> ALLOWED_REASON_CODES = Set.of(
            "semantic_match", "no_business_match", "outside_scope", "ambiguous_candidates",
            "missing_source", "missing_object", "missing_time", "missing_action",
            "compatible_multi_intent", "incompatible_multi_intent", "context_continuation",
            "topic_switch", "general_conversation"
    );
    private static final Set<String> SCHEMA_FIELDS = Set.of(
            "outcome", "primaryCandidateId", "secondaryCandidateIds",
            "rankedCandidateIds", "missingDimensions", "reasonCodes"
    );

    private final PromptLoader promptLoader;
    private final ChatModelRouter chatModelRouter;
    private final ObjectMapper objectMapper;
    private final String classifierModel;

    public StructuredIntentClassifier(PromptLoader promptLoader,
                                      ChatModelRouter chatModelRouter,
                                      ObjectMapper objectMapper,
                                      @Value("${chatagent.intent.classifier-model:}") String classifierModel) {
        this.promptLoader = promptLoader;
        this.chatModelRouter = chatModelRouter;
        this.objectMapper = objectMapper;
        this.classifierModel = classifierModel;
    }

    public Result classify(IntentUnderstandingRequest request, List<IntentCandidate> candidates) {
        try {
            String prompt = promptLoader.render(PromptConstants.INTENT_STRUCTURED_CLASSIFIER, Map.of(
                    "userInputJson", objectMapper.writeValueAsString(request.userInput()),
                    "recentContextJson", objectMapper.writeValueAsString(request.recentTurns()),
                    "sessionAssetsJson", objectMapper.writeValueAsString(request.sessionAssetSummary()),
                    "candidatesJson", candidateJson(candidates)
            ));
            ChatClient chatClient = chatModelRouter.route(classifierModel);
            ChatOptions options = ChatOptions.builder()
                    .temperature(TEMPERATURE)
                    .maxTokens(MAX_TOKENS)
                    .build();
            String content = chatClient.prompt(prompt).options(options).call().content();
            return parseResponse(content, candidates);
        } catch (Exception exception) {
            IntentClassifierFailure failure = isTimeout(exception)
                    ? IntentClassifierFailure.TIMEOUT : IntentClassifierFailure.PROVIDER_FAILURE;
            log.warn("Structured intent classifier failed: failure={}, candidateCount={}",
                    failure, candidates == null ? 0 : candidates.size());
            return Result.failure(failure);
        }
    }

    Result parseResponse(String content, List<IntentCandidate> candidates) {
        if (!StringUtils.hasText(content)) {
            return Result.failure(IntentClassifierFailure.BLANK_RESPONSE);
        }
        try {
            JsonNode root = objectMapper.readTree(content.trim());
            if (root == null || !root.isObject() || !hasExactSchema(root)) {
                return Result.failure(IntentClassifierFailure.MALFORMED_RESPONSE);
            }
            IntentRouteOutcome outcome = IntentRouteOutcome.valueOf(requiredText(root, "outcome"));
            String primary = optionalText(root, "primaryCandidateId");
            List<String> secondary = textArray(root, "secondaryCandidateIds");
            List<String> ranked = textArray(root, "rankedCandidateIds");
            List<MissingDimension> missing = enumArray(root, "missingDimensions", MissingDimension.class);
            List<String> reasons = safeReasonCodes(textArray(root, "reasonCodes"));
            Set<String> allowedIds = new LinkedHashSet<>();
            if (candidates != null) {
                candidates.forEach(candidate -> allowedIds.add(candidate.node().getId()));
            }
            if (!allAllowlisted(primary, secondary, ranked, allowedIds)) {
                return Result.failure(IntentClassifierFailure.UNKNOWN_CANDIDATE_ID);
            }
            if (hasDuplicates(secondary) || hasDuplicates(ranked)
                    || (primary != null && secondary.contains(primary))
                    || !validCombination(outcome, primary, secondary, ranked, missing)) {
                return Result.failure(IntentClassifierFailure.INVALID_CANDIDATE_COMBINATION);
            }
            return new Result(outcome, primary, secondary, ranked, missing, reasons,
                    IntentClassifierFailure.NONE);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            return Result.failure(IntentClassifierFailure.MALFORMED_RESPONSE);
        }
    }

    private boolean hasExactSchema(JsonNode root) {
        Set<String> actual = new LinkedHashSet<>();
        root.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(SCHEMA_FIELDS)) {
            return false;
        }
        return root.get("outcome").isTextual()
                && (root.get("primaryCandidateId").isNull() || root.get("primaryCandidateId").isTextual())
                && root.get("secondaryCandidateIds").isArray()
                && root.get("rankedCandidateIds").isArray()
                && root.get("missingDimensions").isArray()
                && root.get("reasonCodes").isArray();
    }

    private String candidateJson(List<IntentCandidate> candidates) throws com.fasterxml.jackson.core.JsonProcessingException {
        List<Map<String, Object>> values = new ArrayList<>();
        if (candidates != null) {
            for (IntentCandidate candidate : candidates) {
                values.add(Map.of(
                        "id", candidate.node().getId(),
                        "path", candidate.path(),
                        "name", nullToEmpty(candidate.node().getName()),
                        "description", nullToEmpty(candidate.node().getDescription()),
                        "examples", candidate.node().getExamples() == null ? List.of() : candidate.node().getExamples(),
                        "intentKind", candidate.node().getIntentKind() == null ? "UNSPECIFIED" : candidate.node().getIntentKind().name()
                ));
            }
        }
        return objectMapper.writeValueAsString(values);
    }

    private boolean validCombination(IntentRouteOutcome outcome,
                                     String primary,
                                     List<String> secondary,
                                     List<String> ranked,
                                     List<MissingDimension> missing) {
        return switch (outcome) {
            case KNOWN_INTENT -> primary != null && secondary.isEmpty();
            case MULTI_INTENT -> primary != null && !secondary.isEmpty();
            case AMBIGUOUS_ROUTE -> ranked.size() >= 2;
            case EXECUTION_INFO_MISSING -> !missing.isEmpty();
            case GENERAL_CHAT, OUT_OF_DOMAIN -> primary == null && secondary.isEmpty();
        };
    }

    private boolean allAllowlisted(String primary,
                                   List<String> secondary,
                                   List<String> ranked,
                                   Set<String> allowlist) {
        if (primary != null && !allowlist.contains(primary)) {
            return false;
        }
        return allowlist.containsAll(secondary) && allowlist.containsAll(ranked);
    }

    private boolean hasDuplicates(List<String> values) {
        return new LinkedHashSet<>(values).size() != values.size();
    }

    private String requiredText(JsonNode root, String field) {
        String value = optionalText(root, field);
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        return value;
    }

    private String optionalText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() || !value.isTextual() || !StringUtils.hasText(value.asText())
                ? null : value.asText().trim();
    }

    private List<String> textArray(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("Field is not an array: " + field);
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || !StringUtils.hasText(item.asText())) {
                throw new IllegalArgumentException("Array contains invalid value: " + field);
            }
            result.add(item.asText().trim());
        }
        return List.copyOf(result);
    }

    private <E extends Enum<E>> List<E> enumArray(JsonNode root, String field, Class<E> type) {
        return textArray(root, field).stream().map(value -> Enum.valueOf(type, value)).toList();
    }

    private List<String> safeReasonCodes(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String normalized = value.toLowerCase(java.util.Locale.ROOT);
            String safe = ALLOWED_REASON_CODES.contains(normalized)
                    ? normalized : "classifier_reason_unrecognized";
            if (!result.contains(safe) && result.size() < 8) {
                result.add(safe);
            }
        }
        return List.copyOf(result);
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof java.util.concurrent.TimeoutException
                    || cursor.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("timeout")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record Result(
            IntentRouteOutcome outcome,
            String primaryCandidateId,
            List<String> secondaryCandidateIds,
            List<String> rankedCandidateIds,
            List<MissingDimension> missingDimensions,
            List<String> reasonCodes,
            IntentClassifierFailure failure
    ) {
        public Result {
            secondaryCandidateIds = secondaryCandidateIds == null ? List.of() : List.copyOf(secondaryCandidateIds);
            rankedCandidateIds = rankedCandidateIds == null ? List.of() : List.copyOf(rankedCandidateIds);
            missingDimensions = missingDimensions == null ? List.of() : List.copyOf(missingDimensions);
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
            failure = failure == null ? IntentClassifierFailure.NONE : failure;
        }

        public boolean successful() {
            return failure == IntentClassifierFailure.NONE;
        }

        public static Result failure(IntentClassifierFailure failure) {
            return new Result(null, null, List.of(), List.of(), List.of(), List.of(), failure);
        }
    }
}
