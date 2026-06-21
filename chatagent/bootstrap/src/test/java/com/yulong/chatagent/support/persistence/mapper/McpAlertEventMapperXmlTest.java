package com.yulong.chatagent.support.persistence.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class McpAlertEventMapperXmlTest {

    @Test
    void shouldCastStringIdsToUuidForPostgresWrites() throws Exception {
        String xml = new String(
                new ClassPathResource("mapper/McpAlertEventMapper.xml")
                        .getInputStream()
                        .readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(xml).contains("CAST(#{id} AS uuid),");
        assertThat(xml).contains("WHERE id = CAST(#{id} AS uuid)");
    }
}
