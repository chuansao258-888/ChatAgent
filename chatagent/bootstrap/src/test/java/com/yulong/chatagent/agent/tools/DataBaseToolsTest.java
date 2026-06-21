package com.yulong.chatagent.agent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class DataBaseToolsTest {

    @Test
    void shouldRejectWriteSqlWithoutCallingDatabaseOrEchoingSql() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataBaseTools tool = new DataBaseTools(jdbcTemplate);

        String output = tool.query("DELETE FROM t_user WHERE email = 'private@example.test'");

        assertThat(output).isEqualTo("Error: only SELECT statements are supported.");
        assertThat(output).doesNotContain("DELETE")
                .doesNotContain("private@example.test");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void shouldUseJdbcTemplateOnlyForReadOnlySelect() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(eq("SELECT 1 AS answer"), any(ResultSetExtractor.class)))
                .thenReturn(List.of("| answer |", "|--------|", "| 1      |"));
        DataBaseTools tool = new DataBaseTools(jdbcTemplate);

        String output = tool.query("SELECT 1 AS answer");

        assertThat(output).contains("Query result:")
                .contains("| answer |");
        verify(jdbcTemplate).query(eq("SELECT 1 AS answer"), any(ResultSetExtractor.class));
    }

    @Test
    void shouldReturnSafeErrorWhenReadQueryFailsWithoutEchoingSql(CapturedOutput output) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(eq("SELECT secret FROM token_table WHERE token = 'abc123'"),
                any(ResultSetExtractor.class)))
                .thenThrow(new IllegalStateException(
                        "driver failure for SELECT secret FROM token_table WHERE token = 'abc123'"));
        DataBaseTools tool = new DataBaseTools(jdbcTemplate);

        String result = tool.query("SELECT secret FROM token_table WHERE token = 'abc123'");

        assertThat(result).isEqualTo("Error: database query failed.");
        assertThat(result).doesNotContain("token_table")
                .doesNotContain("abc123");
        assertThat(output.getAll()).contains("Database query failed: exception=IllegalStateException")
                .doesNotContain("token_table")
                .doesNotContain("abc123");
    }
}
