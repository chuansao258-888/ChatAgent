package com.yulong.chatagent.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;

/** Canonical JSON and hash shared by approval, execution keys and stale checks. */
public final class ToolArgumentCanonicalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private ToolArgumentCanonicalizer() {
    }

    public static String canonicalize(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = MAPPER.readTree(rawArguments);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("Tool arguments must be a JSON object");
            }
            return MAPPER.writeValueAsString(sortKeysDeep(node));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Tool arguments are malformed JSON", exception);
        }
    }

    public static String sha256(String canonicalArguments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(canonicalArguments.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static JsonNode sortKeysDeep(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            var array = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> array.add(sortKeysDeep(child)));
            return array;
        }
        ArrayList<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        Collections.sort(fields);
        ObjectNode ordered = JsonNodeFactory.instance.objectNode();
        fields.forEach(field -> ordered.set(field, sortKeysDeep(node.get(field))));
        return ordered;
    }
}
