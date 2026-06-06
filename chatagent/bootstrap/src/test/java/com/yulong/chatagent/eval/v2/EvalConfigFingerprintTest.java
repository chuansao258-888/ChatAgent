package com.yulong.chatagent.eval.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalConfigFingerprintTest {

    @Test
    void fingerprintIsIndependentOfMapInsertionOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("topK", 3);
        first.put("rrfK", 60);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("rrfK", 60);
        second.put("topK", 3);

        assertThat(EvalConfigFingerprint.sha256(first)).isEqualTo(EvalConfigFingerprint.sha256(second));
    }

    @Test
    void fingerprintMatchesCrossLanguageParityFixture() throws Exception {
        try (InputStream input = EvalConfigFingerprintTest.class.getResourceAsStream(
                "/eval/v2/fixtures/core-contract-parity.json"
        )) {
            JsonNode fingerprint = new ObjectMapper().readTree(input).get("configFingerprint");
            Map<String, Object> config = new ObjectMapper().convertValue(fingerprint.get("config"), new TypeReference<>() {
            });

            assertThat(EvalConfigFingerprint.sha256(config)).isEqualTo(fingerprint.get("sha256").textValue());
        }
    }
}
