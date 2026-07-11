package com.yulong.chatagent.intent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructuredIntentClassifierTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldAcceptStrictAllowlistedJson() {
        StructuredIntentClassifier classifier = parserOnly();
        String json = """
                {"outcome":"MULTI_INTENT","primaryCandidateId":"a","secondaryCandidateIds":["b"],
                 "rankedCandidateIds":["a","b"],"missingDimensions":[],
                 "reasonCodes":["compatible_multi_intent"]}
                """;

        StructuredIntentClassifier.Result result = classifier.parseResponse(json, candidates("a", "b"));

        assertThat(result.successful()).isTrue();
        assertThat(result.outcome()).isEqualTo(IntentRouteOutcome.MULTI_INTENT);
        assertThat(result.secondaryCandidateIds()).containsExactly("b");
    }

    @Test
    void shouldRejectBlankMalformedUnknownDuplicateAndImpossibleCombinations() {
        StructuredIntentClassifier classifier = parserOnly();

        assertThat(classifier.parseResponse("", candidates("a")).failure())
                .isEqualTo(IntentClassifierFailure.BLANK_RESPONSE);
        assertThat(classifier.parseResponse("not-json", candidates("a")).failure())
                .isEqualTo(IntentClassifierFailure.MALFORMED_RESPONSE);
        assertThat(classifier.parseResponse(
                "{\"outcome\":\"GENERAL_CHAT\",\"primaryCandidateId\":null,"
                        + "\"secondaryCandidateIds\":[],\"rankedCandidateIds\":[],"
                        + "\"missingDimensions\":[]}", candidates("a")).failure())
                .isEqualTo(IntentClassifierFailure.MALFORMED_RESPONSE);
        assertThat(classifier.parseResponse(
                "{\"outcome\":\"GENERAL_CHAT\",\"primaryCandidateId\":null,"
                        + "\"secondaryCandidateIds\":[],\"rankedCandidateIds\":[],"
                        + "\"missingDimensions\":[],\"reasonCodes\":[],\"extra\":true}",
                candidates("a")).failure()).isEqualTo(IntentClassifierFailure.MALFORMED_RESPONSE);
        assertThat(classifier.parseResponse(json("KNOWN_INTENT", "unknown", "[]", "[]"), candidates("a")).failure())
                .isEqualTo(IntentClassifierFailure.UNKNOWN_CANDIDATE_ID);
        assertThat(classifier.parseResponse(json("MULTI_INTENT", "a", "[\"a\"]", "[\"a\"]"), candidates("a")).failure())
                .isEqualTo(IntentClassifierFailure.INVALID_CANDIDATE_COMBINATION);
        assertThat(classifier.parseResponse(json("AMBIGUOUS_ROUTE", null, "[]", "[\"a\"]"), candidates("a")).failure())
                .isEqualTo(IntentClassifierFailure.INVALID_CANDIDATE_COMBINATION);
    }

    @Test
    void shouldMapUntrustedReasonTextToBoundedCode() {
        String rawUserPhrase = "private user sentence";
        String json = """
                {"outcome":"KNOWN_INTENT","primaryCandidateId":"a","secondaryCandidateIds":[],
                 "rankedCandidateIds":["a"],"missingDimensions":[],
                 "reasonCodes":["private user sentence"]}
                """;

        StructuredIntentClassifier.Result result = parserOnly().parseResponse(json, candidates("a"));

        assertThat(result.reasonCodes()).containsExactly("classifier_reason_unrecognized");
        assertThat(result.toString()).doesNotContain(rawUserPhrase);
    }

    @Test
    void shouldClassifyTimeoutAndProviderFailureWithoutRawPayload() {
        PromptLoader promptLoader = mock(PromptLoader.class);
        when(promptLoader.render(anyString(), org.mockito.ArgumentMatchers.anyMap())).thenReturn("safe prompt");
        ChatModelRouter router = mock(ChatModelRouter.class);
        StructuredIntentClassifier classifier = new StructuredIntentClassifier(
                promptLoader, router, mapper, "model");
        IntentUnderstandingRequest request = mock(IntentUnderstandingRequest.class);
        when(request.userInput()).thenReturn("private input");
        when(request.recentTurns()).thenReturn(List.of());

        doThrow(new RuntimeException(new TimeoutException("private input")))
                .when(router).route("model");
        assertThat(classifier.classify(request, candidates("a")).failure())
                .isEqualTo(IntentClassifierFailure.TIMEOUT);

        doThrow(new IllegalStateException("private input")).when(router).route("model");
        StructuredIntentClassifier.Result failure = classifier.classify(request, candidates("a"));
        assertThat(failure.failure()).isEqualTo(IntentClassifierFailure.PROVIDER_FAILURE);
        assertThat(failure.toString()).doesNotContain("private input");
    }

    private StructuredIntentClassifier parserOnly() {
        return new StructuredIntentClassifier(null, null, mapper, "model");
    }

    private List<IntentCandidate> candidates(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> {
            IntentNodeDTO node = IntentNodeDTO.builder().id(id).name(id).intentKind(IntentKind.KB).enabled(true).build();
            return new IntentCandidate(node, id, 1.0d, 0.5d, false, false, 0, List.of());
        }).toList();
    }

    private String json(String outcome, String primary, String secondary, String ranked) {
        String primaryJson = primary == null ? "null" : "\"" + primary + "\"";
        return "{\"outcome\":\"" + outcome + "\",\"primaryCandidateId\":" + primaryJson
                + ",\"secondaryCandidateIds\":" + secondary
                + ",\"rankedCandidateIds\":" + ranked
                + ",\"missingDimensions\":[],\"reasonCodes\":[]}";
    }
}
