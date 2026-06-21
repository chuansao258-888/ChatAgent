package com.yulong.chatagent.support.persistence.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class McpSoftDeleteMapperXmlTest {

    @Test
    void shouldMarkMcpServerRowsDeletedWithoutRemovingAlertForeignKeyTargets() throws Exception {
        String xml = mapper("mapper/McpServerMapper.xml");

        assertThat(xml).contains("UPDATE t_mcp_server");
        assertThat(xml).contains("deleted_at = #{deletedAt}");
        assertThat(xml).contains("updated_at = #{updatedAt}");
        assertThat(xml).doesNotContain("DELETE FROM t_mcp_server");
    }

    @Test
    void shouldMarkMcpToolCatalogRowsDeletedWithoutRemovingHistory() throws Exception {
        String xml = mapper("mapper/McpToolCatalogMapper.xml");

        assertThat(xml).contains("UPDATE t_mcp_tool_catalog");
        assertThat(xml).contains("deleted_at = #{deletedAt}");
        assertThat(xml).contains("updated_at = #{updatedAt}");
        assertThat(xml).doesNotContain("DELETE FROM t_mcp_tool_catalog");
    }

    private static String mapper(String resourcePath) throws Exception {
        return new String(
                new ClassPathResource(resourcePath).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
    }
}
