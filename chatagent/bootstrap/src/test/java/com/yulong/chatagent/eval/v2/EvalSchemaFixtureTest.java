package com.yulong.chatagent.eval.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalSchemaFixtureTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void sharedFixturesMatchCanonicalSchemas() throws Exception {
        validateFixture("eval-sample.schema.json", "sample-valid.json");
        validateFixture("eval-report.schema.json", "report-valid.json");
        validateFixture("eval-run-manifest.schema.json", "manifest-valid.json");
        validateFixture("eval-parameter-space.schema.json", "parameter-space-valid.json");
        validateFixture("eval-trial.schema.json", "trial-valid.json");
    }

    @Test
    void schemaRejectsMissingRequiredField() throws Exception {
        JsonNode sample = load("/eval/v2/fixtures/sample-valid.json");
        ((com.fasterxml.jackson.databind.node.ObjectNode) sample).remove("sampleId");

        assertThatThrownBy(() -> validate(sample, load("/eval/v2/schemas/eval-sample.schema.json"), "$"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleId");
    }

    @Test
    void approvedSourceCatalogsMatchCanonicalSchema() throws Exception {
        validateResource("eval-source-catalog.schema.json", "/eval/v2/corpora/catalog/beir-scifact.json");
        validateResource("eval-source-catalog.schema.json", "/eval/v2/corpora/catalog/mtrag-human.json");
        validateResource("eval-source-catalog.schema.json", "/eval/v2/corpora/catalog/sec-edgar-companyfacts.json");
    }

    private void validateFixture(String schemaName, String fixtureName) throws Exception {
        validateResource(schemaName, "/eval/v2/fixtures/" + fixtureName);
    }

    private void validateResource(String schemaName, String resourcePath) throws Exception {
        validate(load(resourcePath), load("/eval/v2/schemas/" + schemaName), "$");
    }

    private JsonNode load(String path) throws IOException {
        try (InputStream input = EvalSchemaFixtureTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing test resource: " + path);
            }
            return OBJECT_MAPPER.readTree(input);
        }
    }

    private void validate(JsonNode instance, JsonNode schema, String path) {
        validateType(instance, schema.get("type"), path);
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && !contains(enumValues, instance)) {
            throw new IllegalArgumentException(path + " must match enum");
        }
        if (instance.isTextual() && schema.has("minLength") && instance.textValue().length() < schema.get("minLength").intValue()) {
            throw new IllegalArgumentException(path + " is shorter than minLength");
        }
        if (instance.isArray()) {
            if (schema.has("minItems") && instance.size() < schema.get("minItems").intValue()) {
                throw new IllegalArgumentException(path + " has fewer than minItems");
            }
            if (schema.path("uniqueItems").asBoolean(false)) {
                Set<String> unique = new HashSet<>();
                instance.forEach(item -> unique.add(item.toString()));
                if (unique.size() != instance.size()) {
                    throw new IllegalArgumentException(path + " must contain unique items");
                }
            }
            if (schema.has("items")) {
                for (int index = 0; index < instance.size(); index++) {
                    validate(instance.get(index), schema.get("items"), path + "[" + index + "]");
                }
            }
        }
        if (instance.isObject()) {
            schema.path("required").forEach(required -> {
                if (!instance.has(required.textValue())) {
                    throw new IllegalArgumentException(path + " missing required field: " + required.textValue());
                }
            });
            JsonNode properties = schema.path("properties");
            JsonNode additional = schema.get("additionalProperties");
            Iterator<Map.Entry<String, JsonNode>> fields = instance.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (properties.has(field.getKey())) {
                    validate(field.getValue(), properties.get(field.getKey()), path + "." + field.getKey());
                } else if (additional != null && additional.isObject()) {
                    validate(field.getValue(), additional, path + "." + field.getKey());
                } else if (additional != null && additional.isBoolean() && !additional.booleanValue()) {
                    throw new IllegalArgumentException(path + " contains unknown field: " + field.getKey());
                }
            }
        }
    }

    private void validateType(JsonNode instance, JsonNode expected, String path) {
        if (expected == null) {
            return;
        }
        if (expected.isArray()) {
            if (!containsType(expected, instance)) {
                throw new IllegalArgumentException(path + " has invalid type");
            }
            return;
        }
        if (!matchesType(instance, expected.textValue())) {
            throw new IllegalArgumentException(path + " has invalid type");
        }
    }

    private boolean contains(JsonNode array, JsonNode expected) {
        for (JsonNode item : array) {
            if (item.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsType(JsonNode types, JsonNode instance) {
        for (JsonNode type : types) {
            if (matchesType(instance, type.textValue())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesType(JsonNode instance, String type) {
        return switch (type) {
            case "null" -> instance.isNull();
            case "object" -> instance.isObject();
            case "array" -> instance.isArray();
            case "string" -> instance.isTextual();
            case "boolean" -> instance.isBoolean();
            case "integer" -> instance.isIntegralNumber();
            case "number" -> instance.isNumber();
            default -> true;
        };
    }
}
