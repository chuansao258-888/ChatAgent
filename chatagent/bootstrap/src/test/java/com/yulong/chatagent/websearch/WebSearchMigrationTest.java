package com.yulong.chatagent.websearch;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchMigrationTest {

    @Test
    void phase3MigrationShouldAppendWebSearchToolToBuiltinAssistant() throws Exception {
        ClassPathResource migration = new ClassPathResource(
                "db/migration/V19__add_web_search_tool_to_builtin_assistant.sql");
        String sql = migration.getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("3f9f84f7-2df0-4a5f-9c85-9f2d9b7aaf10")
                .contains("webSearchTool")
                .contains("allowed_tools ? 'webSearchTool'")
                .contains("allowed_tools || '[\"webSearchTool\"]'::jsonb");
    }
}
