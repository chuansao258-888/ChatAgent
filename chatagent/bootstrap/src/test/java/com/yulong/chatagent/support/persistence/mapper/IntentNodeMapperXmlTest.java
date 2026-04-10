package com.yulong.chatagent.support.persistence.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class IntentNodeMapperXmlTest {

    @Test
    void shouldUseNullIfForNullableParentUuidBinding() throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream("mapper/IntentNodeMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(xml).contains("CAST(NULLIF(#{parentId}, '') AS uuid)");
            assertThat(xml).contains("CAST(NULLIF(#{item.parentId}, '') AS uuid)");
            assertThat(xml).doesNotContain("CASE WHEN #{parentId} IS NULL");
            assertThat(xml).doesNotContain("CASE WHEN #{item.parentId} IS NULL");
        }
    }
}
