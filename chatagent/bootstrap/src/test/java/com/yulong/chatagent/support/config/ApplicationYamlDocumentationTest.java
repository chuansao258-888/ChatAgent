package com.yulong.chatagent.support.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationYamlDocumentationTest {

    private static final List<String> APPLICATION_YAMLS = List.of(
            "application.yaml",
            "application-capacity-test.yaml",
            "application-resilience-test.yaml",
            "application-eval-real-doc-ingestion.yaml",
            "application-eval-real-memory.yaml");

    private static final Set<String> RUNTIME_SECRET_NAMES = Set.of(
            "CHATAGENT_DB_PASSWORD",
            "CHATAGENT_REDIS_PASSWORD",
            "CHATAGENT_RABBITMQ_PASSWORD",
            "CHATAGENT_DEEPSEEK_API_KEY",
            "CHATAGENT_ZAI_CODING_API_KEY",
            "CHATAGENT_RAG_RERANKER_API_KEY",
            "CHATAGENT_RAG_VDP_MINERU_BEARER_TOKEN",
            "CHATAGENT_MILVUS_PASSWORD",
            "CHATAGENT_WEB_SEARCH_BRAVE_API_KEY",
            "CHATAGENT_MCP_CIPHER_KEY",
            "CHATAGENT_JWT_SECRET");

    private static final Set<String> ENV_EXAMPLE_SECRET_NAMES = Set.of(
            "CHATAGENT_DB_PASSWORD",
            "CHATAGENT_REDIS_PASSWORD",
            "CHATAGENT_RABBITMQ_PASSWORD",
            "CHATAGENT_DEEPSEEK_API_KEY",
            "CHATAGENT_ZAI_CODING_API_KEY",
            "CHATAGENT_ZHIPUAI_API_KEY",
            "CHATAGENT_ZHIPUAI_API_KEY_2",
            "CHATAGENT_RAG_RERANKER_API_KEY",
            "CHATAGENT_RAG_VDP_MINERU_BEARER_TOKEN",
            "CHATAGENT_MILVUS_PASSWORD",
            "CHATAGENT_WEB_SEARCH_BRAVE_API_KEY",
            "CHATAGENT_MCP_CIPHER_KEY",
            "CHATAGENT_JWT_SECRET");

    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+)(?::[^}]*)?}");
    private static final Pattern ENV_ASSIGNMENT = Pattern.compile("^([A-Z0-9_]+)=(.*)$");

    @Test
    void shouldLoadAndDocumentEveryApplicationYamlDataLine() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

        for (String resourceName : APPLICATION_YAMLS) {
            ClassPathResource resource = new ClassPathResource(resourceName);
            assertThat(loader.load(resourceName, resource))
                    .as("parsed property sources for %s", resourceName)
                    .isNotEmpty();

            List<String> lines = resource.getContentAsString(StandardCharsets.UTF_8).lines().toList();
            for (int index = 0; index < lines.size(); index++) {
                String trimmed = lines.get(index).trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                assertThat(lines.get(index))
                        .as("meaningful inline comment at %s:%s", resourceName, index + 1)
                        .contains(" # ");
                String comment = lines.get(index).substring(lines.get(index).indexOf(" # ") + 3);
                assertThat(comment)
                        .as("non-placeholder comment at %s:%s", resourceName, index + 1)
                        .isNotBlank()
                        .doesNotContain(
                                "limit or threshold",
                                "maximum allowed",
                                "minimum allowed",
                                " value.");
            }
        }
    }

    @Test
    void shouldUseEnvironmentPlaceholdersOnlyForApprovedRuntimeSecrets() throws IOException {
        Set<String> actualNames = new LinkedHashSet<>();

        for (String resourceName : APPLICATION_YAMLS) {
            String content = new ClassPathResource(resourceName)
                    .getContentAsString(StandardCharsets.UTF_8);
            Matcher matcher = ENV_PLACEHOLDER.matcher(content);
            while (matcher.find()) {
                actualNames.add(matcher.group(1));
            }
        }

        assertThat(actualNames).containsExactlyInAnyOrderElementsOf(RUNTIME_SECRET_NAMES);
    }

    @Test
    void shouldKeepEnvExampleLimitedToEmptySecretAssignments() throws IOException {
        Path moduleRoot = Path.of(System.getProperty("basedir")).toAbsolutePath().normalize();
        Path envExample = moduleRoot.getParent().resolve(".env.example");
        Set<String> actualNames = new LinkedHashSet<>();

        for (String line : Files.readAllLines(envExample, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            Matcher matcher = ENV_ASSIGNMENT.matcher(trimmed);
            assertThat(matcher.matches()).as("environment assignment: %s", line).isTrue();
            actualNames.add(matcher.group(1));
            assertThat(matcher.group(2)).as("empty example value for %s", matcher.group(1)).isEmpty();
        }

        assertThat(actualNames).containsExactlyInAnyOrderElementsOf(ENV_EXAMPLE_SECRET_NAMES);
    }

    @Test
    void shouldUseOneNormalRuntimeYamlWithLocalServiceDefaults() throws IOException {
        Path moduleRoot = Path.of(System.getProperty("basedir")).toAbsolutePath().normalize();
        Path mainResources = moduleRoot.resolve("src/main/resources");
        Path applicationYaml = mainResources.resolve("application.yaml");

        assertThat(applicationYaml).isRegularFile();
        assertThat(mainResources.resolve("application-local-gpu.yaml")).doesNotExist();

        String content = Files.readString(applicationYaml, StandardCharsets.UTF_8);
        assertThat(content).doesNotContain("default: local-gpu", "on-profile: local-gpu");

        var sources = new YamlPropertySourceLoader().load(
                "application", new ClassPathResource("application.yaml"));
        assertThat(property(sources, "rag.embedding.base-url")).isEqualTo("http://127.0.0.1:11434");
        assertThat(property(sources, "rag.embedding.model")).isEqualTo("bge-m3");
        assertThat(property(sources, "rag.retrieval.reranker.provider")).isEqualTo("bge-http");
        assertThat(property(sources, "rag.retrieval.reranker.model-id")).isEqualTo("BAAI/bge-reranker-v2-m3");
        assertThat(property(sources, "rag.retrieval.reranker.base-url")).isEqualTo("http://127.0.0.1:7997");
        assertThat(property(sources, "rag.retrieval.reranker.path")).isEqualTo("/rerank");
        assertThat(property(sources, "rag.retrieval.reranker.ready-path")).isEqualTo("/ready");
        assertThat(property(sources, "chatagent.rag.vdp.routing.knowledge-batch-preferred")).isEqualTo(true);
        assertThat(property(sources, "chatagent.rag.vdp.routing.preferred-batch-engine")).isEqualTo("mineru");
        assertThat(property(sources, "chatagent.rag.vdp.mineru.enabled")).isEqualTo(true);
        assertThat(property(sources, "chatagent.rag.vdp.mineru.base-url")).isEqualTo("http://127.0.0.1:8000");
        assertThat(property(sources, "chatagent.rag.vdp.mineru.bearer-token"))
                .isEqualTo("${CHATAGENT_RAG_VDP_MINERU_BEARER_TOKEN:}");
    }

    private static Object property(List<PropertySource<?>> sources, String name) {
        return sources.stream()
                .map(source -> source.getProperty(name))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing application property: " + name));
    }
}
