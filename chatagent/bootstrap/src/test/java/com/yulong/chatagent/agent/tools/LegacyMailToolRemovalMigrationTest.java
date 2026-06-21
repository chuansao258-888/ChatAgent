package com.yulong.chatagent.agent.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyMailToolRemovalMigrationTest {

    @Test
    void cleanupMigrationShouldRemoveLegacyEmailToolReferences() throws Exception {
        ClassPathResource migration = new ClassPathResource(
                "db/migration/V27__remove_legacy_email_tool.sql");
        String sql = migration.getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("UPDATE agent")
                .contains("UPDATE intent_node")
                .contains("allowed_tools - 'emailTool'")
                .contains("to_regclass('public.agent_template')")
                .contains("THEN FALSE");
    }

    @Test
    void runtimeConfigShouldNotKeepMailHealthWorkaroundAfterMailRemoval() throws Exception {
        Path moduleDir = Path.of(System.getProperty("user.dir"));

        assertNoActiveMailRuntimeConfig(moduleDir.resolve("src/main/resources/application.yaml"));
        assertNoActiveMailRuntimeConfig(moduleDir.resolve("../.env.example"));
        assertNoActiveMailRuntimeConfig(moduleDir.resolve("../../docs/env_variables.txt"));
    }

    @Test
    void mavenModulesShouldNotDependOnSpringMailStarter() throws Exception {
        Path moduleDir = Path.of(System.getProperty("user.dir"));

        assertThat(Files.readString(moduleDir.resolve("../pom.xml"))).doesNotContain("spring-boot-starter-mail");
        assertThat(Files.readString(moduleDir.resolve("pom.xml"))).doesNotContain("spring-boot-starter-mail");
        assertThat(Files.readString(moduleDir.resolve("../infra/pom.xml"))).doesNotContain("spring-boot-starter-mail");
    }

    private static void assertNoActiveMailRuntimeConfig(Path path) throws Exception {
        String content = Files.readString(path);

        assertThat(content)
                .doesNotContain("MANAGEMENT_HEALTH_MAIL_ENABLED")
                .doesNotContain("management.health.mail")
                .doesNotContain("spring.mail")
                .doesNotContain("CHATAGENT_MAIL_");
    }
}
