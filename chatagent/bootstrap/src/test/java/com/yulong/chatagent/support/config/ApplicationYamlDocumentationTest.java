package com.yulong.chatagent.support.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
            "CHATAGENT_MINIO_ACCESS_KEY",
            "CHATAGENT_MINIO_SECRET_KEY",
            "CHATAGENT_WEB_SEARCH_BRAVE_API_KEY",
            "CHATAGENT_MCP_CIPHER_KEY",
            "CHATAGENT_JWT_SECRET");

    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+)(?::[^}]*)?}");
    private static final Pattern SECRET_PLACEHOLDER_WITH_FALLBACK =
            Pattern.compile("\\$\\{([A-Z0-9_]+):([^}]*)}");
    private static final Pattern ENV_ASSIGNMENT = Pattern.compile("^([A-Z0-9_]+)=(.*)$");
    private static final Set<String> REQUIRED_LOCAL_INFRA_SECRET_NAMES = Set.of(
            "CHATAGENT_DB_PASSWORD",
            "CHATAGENT_REDIS_PASSWORD",
            "CHATAGENT_RABBITMQ_PASSWORD",
            "CHATAGENT_MINIO_ACCESS_KEY",
            "CHATAGENT_MINIO_SECRET_KEY");

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
    void shouldKeepNormalRuntimeSecretFallbacksEmpty() throws IOException {
        String content = new ClassPathResource("application.yaml")
                .getContentAsString(StandardCharsets.UTF_8);
        Matcher matcher = SECRET_PLACEHOLDER_WITH_FALLBACK.matcher(content);
        Set<String> actualNames = new LinkedHashSet<>();

        while (matcher.find()) {
            String name = matcher.group(1);
            actualNames.add(name);
            assertThat(matcher.group(2).isEmpty())
                    .as("empty normal-runtime secret fallback for %s", name)
                    .isTrue();
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
            assertThat(matcher.group(2).isEmpty())
                    .as("empty example value for %s", matcher.group(1))
                    .isTrue();
        }

        assertThat(actualNames).containsExactlyInAnyOrderElementsOf(ENV_EXAMPLE_SECRET_NAMES);
    }

    @Test
    void shouldKeepPresentLocalSecretFileLimitedToConfiguredValues() throws IOException {
        Path moduleRoot = Path.of(System.getProperty("basedir")).toAbsolutePath().normalize();
        Path localSecrets = moduleRoot.getParent().getParent().resolve("docs/env_variables.txt");
        assumeTrue(Files.isRegularFile(localSecrets), "ignored local secret file is not present");

        Set<String> actualNames = new LinkedHashSet<>();
        List<String> lines = Files.readAllLines(localSecrets, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String trimmed = lines.get(index).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            Matcher matcher = ENV_ASSIGNMENT.matcher(trimmed);
            assertThat(matcher.matches())
                    .as("local secret assignment at line %s", index + 1)
                    .isTrue();
            if (!matcher.matches()) {
                continue;
            }
            String name = matcher.group(1);
            actualNames.add(name);
            assertThat(name).isIn(ENV_EXAMPLE_SECRET_NAMES);
            assertThat(matcher.group(2)).as("configured local value for %s", name).isNotBlank();
        }

        assertThat(actualNames).isSubsetOf(ENV_EXAMPLE_SECRET_NAMES);
        assertThat(actualNames).containsAll(REQUIRED_LOCAL_INFRA_SECRET_NAMES);
    }

    @Test
    void shouldReferenceOneSecretSourceFromBothComposeModes() throws IOException {
        Path moduleRoot = Path.of(System.getProperty("basedir")).toAbsolutePath().normalize();
        Path repositoryRoot = moduleRoot.getParent().getParent();
        Path normalCompose = repositoryRoot.resolve("docker/compose.yaml");
        Path loadTestCompose = repositoryRoot.resolve("docker/compose.load-test.yaml");
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> normalSources = loader.load(
                "normal-compose", new FileSystemResource(normalCompose));
        List<PropertySource<?>> loadTestSources = loader.load(
                "load-test-compose", new FileSystemResource(loadTestCompose));

        assertSecretReference(
                normalSources,
                "services.postgres.environment.POSTGRES_PASSWORD",
                "CHATAGENT_DB_PASSWORD");
        assertSecretReference(
                normalSources,
                "services.redis.environment.REDIS_PASSWORD",
                "CHATAGENT_REDIS_PASSWORD");
        assertSecretReference(
                normalSources,
                "services.rabbitmq.environment.RABBITMQ_DEFAULT_PASS",
                "CHATAGENT_RABBITMQ_PASSWORD");
        assertSecretReference(
                normalSources,
                "services.minio.environment.MINIO_ACCESS_KEY",
                "CHATAGENT_MINIO_ACCESS_KEY");
        assertSecretReference(
                normalSources,
                "services.minio.environment.MINIO_SECRET_KEY",
                "CHATAGENT_MINIO_SECRET_KEY");

        assertSecretReference(
                loadTestSources,
                "services.postgres.environment.POSTGRES_PASSWORD",
                "CHATAGENT_DB_PASSWORD");
        assertSecretReference(
                loadTestSources,
                "services.redis.environment.REDIS_PASSWORD",
                "CHATAGENT_REDIS_PASSWORD");
        assertSecretReference(
                loadTestSources,
                "services.rabbitmq.environment.RABBITMQ_DEFAULT_PASS",
                "CHATAGENT_RABBITMQ_PASSWORD");

        assertConfigurationValue(
                normalSources,
                "services.redis.command[2]",
                "exec redis-server --requirepass \"$${REDIS_PASSWORD}\"");
        assertConfigurationValue(
                normalSources,
                "services.redis.healthcheck.test[1]",
                "REDISCLI_AUTH=\"$${REDIS_PASSWORD}\" redis-cli ping");
        assertConfigurationValue(
                loadTestSources,
                "services.redis.command[2]",
                "exec redis-server --requirepass \"$${REDIS_PASSWORD}\"");
        assertConfigurationValue(
                loadTestSources,
                "services.redis.healthcheck.test[1]",
                "REDISCLI_AUTH=\"$${REDIS_PASSWORD}\" redis-cli ping");
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
        assertThat(property(sources, "spring.config.import"))
                .isEqualTo("optional:file:${chatagent.secrets.file:__chatagent_secrets_not_configured__}[.properties]");
        assertThat(property(sources, "rag.embedding.model")).isEqualTo("bge-m3");
        assertThat(property(sources, "rag.retrieval.reranker.provider")).isEqualTo("bge-http");
        assertThat(property(sources, "rag.retrieval.reranker.model-id")).isEqualTo("BAAI/bge-reranker-v2-m3");
        assertThat(property(sources, "rag.retrieval.reranker.base-url")).isEqualTo("http://127.0.0.1:7997");
        assertThat(property(sources, "rag.retrieval.reranker.path")).isEqualTo("/rerank");
        assertThat(property(sources, "rag.retrieval.reranker.ready-path")).isEqualTo("/ready");
        assertThat(property(sources, "spring.rabbitmq.password"))
                .isEqualTo("${CHATAGENT_RABBITMQ_PASSWORD:}");
        assertThat(property(sources, "rag.retrieval.reranker.api-key"))
                .isEqualTo("${CHATAGENT_RAG_RERANKER_API_KEY:}");
        assertThat(property(sources, "milvus.password")).isEqualTo("${CHATAGENT_MILVUS_PASSWORD:}");
        assertThat(property(sources, "chatagent.rag.vdp.routing.knowledge-batch-preferred")).isEqualTo(true);
        assertThat(property(sources, "chatagent.rag.vdp.routing.preferred-batch-engine")).isEqualTo("mineru");
        assertThat(property(sources, "chatagent.rag.vdp.mineru.enabled")).isEqualTo(true);
        assertThat(property(sources, "chatagent.rag.vdp.mineru.base-url")).isEqualTo("http://127.0.0.1:8000");
        assertThat(property(sources, "chatagent.rag.vdp.mineru.bearer-token"))
                .isEqualTo("${CHATAGENT_RAG_VDP_MINERU_BEARER_TOKEN:}");
        assertThat(property(sources, "chatagent.mcp.crypto.key"))
                .isEqualTo("${CHATAGENT_MCP_CIPHER_KEY:}");
    }

    private static Object property(List<PropertySource<?>> sources, String name) {
        return sources.stream()
                .map(source -> source.getProperty(name))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing application property: " + name));
    }

    private static String requiredSecret(String name) {
        return "${" + name + ":?" + name + " is required}";
    }

    private static void assertSecretReference(
            List<PropertySource<?>> sources,
            String propertyName,
            String environmentName) {
        assertThat(requiredSecret(environmentName).equals(property(sources, propertyName)))
                .as("%s references %s without exposing a value", propertyName, environmentName)
                .isTrue();
    }

    private static void assertConfigurationValue(
            List<PropertySource<?>> sources,
            String propertyName,
            String expectedValue) {
        assertThat(expectedValue.equals(property(sources, propertyName)))
                .as("expected non-secret wiring for %s", propertyName)
                .isTrue();
    }
}
