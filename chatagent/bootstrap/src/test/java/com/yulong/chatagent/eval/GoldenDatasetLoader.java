package com.yulong.chatagent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Loads golden dataset JSON files from classpath eval/golden/.
 * Supports smoke mode via system property eval.smoke=true which
 * returns only the first N entries per category (default N=5).
 */
public final class GoldenDatasetLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String BASE_PATH = "eval/golden/";
    private static final int SMOKE_COUNT = Integer.getInteger("eval.smoke.count", 5);

    private GoldenDatasetLoader() {}

    public static List<IntentGoldenEntry> loadIntentGolden() {
        return load("intent-golden.json", new TypeReference<List<IntentGoldenEntry>>() {});
    }

    public static List<IntentGoldenEntry> loadIntentGoldenSmoke() {
        return loadSmokeByCategory(loadIntentGolden(), IntentGoldenEntry::category);
    }

    public static List<RagGoldenEntry> loadRagGolden() {
        return load("rag-golden.json", new TypeReference<List<RagGoldenEntry>>() {});
    }

    public static List<RagGoldenEntry> loadRagGoldenSmoke() {
        return loadSmokeByCategory(loadRagGolden(), RagGoldenEntry::category);
    }

    public static List<MemoryGoldenDialogue> loadMemoryGolden() {
        return load("memory-golden.json", new TypeReference<List<MemoryGoldenDialogue>>() {});
    }

    public static List<MultiturnGoldenDialogue> loadMultiturnGolden() {
        return load("multiturn-golden.json", new TypeReference<List<MultiturnGoldenDialogue>>() {});
    }

    public static List<ToolGoldenScenario> loadToolGolden() {
        return load("tool-golden.json", new TypeReference<List<ToolGoldenScenario>>() {});
    }

    public static <T> List<T> load(String filename, TypeReference<List<T>> typeRef) {
        String path = BASE_PATH + filename;
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Golden dataset not found on classpath: " + path);
            }
            return MAPPER.readValue(is, typeRef);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load golden dataset: " + path, e);
        }
    }

    /**
     * Returns only the first SMOKE_COUNT entries per category.
     */
    private static <T> List<T> loadSmokeByCategory(List<T> entries, java.util.function.Function<T, String> categoryExtractor) {
        Map<String, List<T>> byCategory = entries.stream()
                .collect(Collectors.groupingBy(categoryExtractor));
        return byCategory.values().stream()
                .flatMap(list -> list.stream().limit(SMOKE_COUNT))
                .toList();
    }
}
