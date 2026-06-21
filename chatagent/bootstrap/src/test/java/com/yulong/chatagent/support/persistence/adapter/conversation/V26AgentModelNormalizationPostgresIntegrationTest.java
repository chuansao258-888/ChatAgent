package com.yulong.chatagent.support.persistence.adapter.conversation;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class V26AgentModelNormalizationPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
    );

    @Test
    void shouldNormalizeIdsProducedByHistoricalV24() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS agent_template");
        jdbcTemplate.execute("DROP TABLE IF EXISTS agent");
        jdbcTemplate.execute("CREATE TABLE agent (id TEXT PRIMARY KEY, model TEXT NOT NULL, chat_options JSONB)");
        jdbcTemplate.execute("CREATE TABLE agent_template (id TEXT PRIMARY KEY, model TEXT NOT NULL, chat_options JSONB NOT NULL DEFAULT '{}'::jsonb)");
        jdbcTemplate.update("INSERT INTO agent (id, model) VALUES (?, ?)", "chat", "deepseek-chat");
        jdbcTemplate.update("INSERT INTO agent (id, model) VALUES (?, ?)", "reasoning", "deepseek-reasoner");
        jdbcTemplate.update("INSERT INTO agent (id, model, chat_options) VALUES (?, ?, ?::jsonb)",
                "glm", "glm-4.6", "{\"messageLength\":12,\"tokenBudget\":4000}");
        jdbcTemplate.update("INSERT INTO agent (id, model, chat_options) VALUES (?, ?, ?::jsonb)",
                "unchanged", "custom-model", "{\"messageLength\":\"oops\",\"tokenBudget\":\"oops\"}");
        jdbcTemplate.update("INSERT INTO agent_template (id, model, chat_options) VALUES (?, ?, ?::jsonb)",
                "template", "glm-4.7", "{\"messageLength\":10,\"tokenBudget\":4000}");

        executeMigration(dataSource, "db/migration/V24__model_catalog_agent_model_ids.sql");
        assertThat(model(jdbcTemplate, "chat")).isEqualTo("chat-fast");
        assertThat(model(jdbcTemplate, "reasoning")).isEqualTo("chat-reasoning");
        assertThat(model(jdbcTemplate, "glm")).isEqualTo("chat-default");

        executeMigration(dataSource, "db/migration/V25__upgrade_agent_model_ids.sql");
        executeMigration(dataSource, "db/migration/V26__normalize_agent_model_ids.sql");
        assertThat(model(jdbcTemplate, "chat")).isEqualTo("deepseek-v4-flash");
        assertThat(model(jdbcTemplate, "reasoning")).isEqualTo("deepseek-v4-pro");
        assertThat(model(jdbcTemplate, "glm")).isEqualTo("glm-4.7");
        assertThat(model(jdbcTemplate, "unchanged")).isEqualTo("custom-model");

        executeMigration(dataSource, "db/migration/V28__upgrade_agent_primary_to_glm_5_2_1m.sql");
        assertThat(model(jdbcTemplate, "glm")).isEqualTo("glm-5.2[1m]");
        assertThat(model(jdbcTemplate, "unchanged")).isEqualTo("custom-model");
        assertThat(chatOptionInt(jdbcTemplate, "glm", "messageLength")).isEqualTo(80);
        assertThat(chatOptionInt(jdbcTemplate, "glm", "tokenBudget")).isEqualTo(128000);
        assertThat(chatOptionInt(jdbcTemplate, "unchanged", "messageLength")).isEqualTo(80);
        assertThat(chatOptionInt(jdbcTemplate, "unchanged", "tokenBudget")).isEqualTo(128000);
        assertThat(templateModel(jdbcTemplate, "template")).isEqualTo("glm-5.2[1m]");
        assertThat(templateChatOptionInt(jdbcTemplate, "template", "messageLength")).isEqualTo(80);
        assertThat(templateChatOptionInt(jdbcTemplate, "template", "tokenBudget")).isEqualTo(128000);

        executeMigration(dataSource, "db/migration/V29__normalize_glm_5_2_api_model_id.sql");
        assertThat(model(jdbcTemplate, "glm")).isEqualTo("glm-5.2");
        assertThat(templateModel(jdbcTemplate, "template")).isEqualTo("glm-5.2");

        executeMigration(dataSource, "db/migration/V30__raise_agent_context_window_for_glm_5_2.sql");
        assertThat(chatOptionInt(jdbcTemplate, "glm", "messageLength")).isEqualTo(120);
        assertThat(chatOptionInt(jdbcTemplate, "glm", "tokenBudget")).isEqualTo(256000);
        assertThat(chatOptionInt(jdbcTemplate, "unchanged", "messageLength")).isEqualTo(120);
        assertThat(chatOptionInt(jdbcTemplate, "unchanged", "tokenBudget")).isEqualTo(256000);
        assertThat(templateChatOptionInt(jdbcTemplate, "template", "messageLength")).isEqualTo(120);
        assertThat(templateChatOptionInt(jdbcTemplate, "template", "tokenBudget")).isEqualTo(256000);
    }

    @Test
    void shouldAllowGlm52MigrationWhenHistoricalTemplateTableIsAbsent() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS agent_template");
        jdbcTemplate.execute("DROP TABLE IF EXISTS agent");
        jdbcTemplate.execute("CREATE TABLE agent (id TEXT PRIMARY KEY, model TEXT NOT NULL, chat_options JSONB)");
        jdbcTemplate.update("INSERT INTO agent (id, model, chat_options) VALUES (?, ?, ?::jsonb)",
                "glm", "glm-4.7", "{\"messageLength\":10,\"tokenBudget\":4000}");

        executeMigration(dataSource, "db/migration/V28__upgrade_agent_primary_to_glm_5_2_1m.sql");
        executeMigration(dataSource, "db/migration/V29__normalize_glm_5_2_api_model_id.sql");
        executeMigration(dataSource, "db/migration/V30__raise_agent_context_window_for_glm_5_2.sql");

        assertThat(model(jdbcTemplate, "glm")).isEqualTo("glm-5.2");
        assertThat(chatOptionInt(jdbcTemplate, "glm", "messageLength")).isEqualTo(120);
        assertThat(chatOptionInt(jdbcTemplate, "glm", "tokenBudget")).isEqualTo(256000);
    }

    private static void executeMigration(DataSource dataSource, String resourcePath) throws Exception {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String model(JdbcTemplate jdbcTemplate, String id) {
        return jdbcTemplate.queryForObject("SELECT model FROM agent WHERE id = ?", String.class, id);
    }

    private static Integer chatOptionInt(JdbcTemplate jdbcTemplate, String id, String key) {
        return jdbcTemplate.queryForObject(
                "SELECT (chat_options ->> ?)::int FROM agent WHERE id = ?",
                Integer.class,
                key,
                id);
    }

    private static String templateModel(JdbcTemplate jdbcTemplate, String id) {
        return jdbcTemplate.queryForObject("SELECT model FROM agent_template WHERE id = ?", String.class, id);
    }

    private static Integer templateChatOptionInt(JdbcTemplate jdbcTemplate, String id, String key) {
        return jdbcTemplate.queryForObject(
                "SELECT (chat_options ->> ?)::int FROM agent_template WHERE id = ?",
                Integer.class,
                key,
                id);
    }
}
