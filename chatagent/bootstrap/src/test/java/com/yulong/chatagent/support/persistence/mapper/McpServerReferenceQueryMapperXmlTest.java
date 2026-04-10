package com.yulong.chatagent.support.persistence.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerReferenceQueryMapperXmlTest {

    @Test
    void shouldUseJdbcSafeJsonbExistsAnyInsteadOfQuestionMarkOperator() throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream("mapper/McpServerReferenceQueryMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(xml).contains("jsonb_exists_any(");
            assertThat(xml).doesNotContain("?|");
        }
    }
}
