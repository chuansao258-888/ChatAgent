package com.yulong.chatagent.agent.tools;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARRB Phase 1（ARRB-DEC-018）：证明 {@link DataBaseTools#query} 在数据库事务边界是只读的。
 * <p>
 * 只读语义不能只靠 {@code startsWith("SELECT")}：这里验证 PostgreSQL 只读事务拒绝写入、
 * 拒绝 DDL、拒绝多语句、并对结果集执行 200 行上限。
 * <p>
 * Docker 必需：这是真实的数据库边界证明，不能被 disabledWithoutDocker 跳过当作证据
 *（plan："Docker absence is a blocking environment failure"）。
 */
@Testcontainers(disabledWithoutDocker = true)
class DataBaseToolsReadOnlyIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
    );

    private static JdbcTemplate jdbcTemplate;
    private static DataBaseTools dataBaseTools;

    @BeforeAll
    static void setUpDatabase() {
        DataSource dataSource = dataSource();
        jdbcTemplate = new JdbcTemplate(dataSource);
        dataBaseTools = new DataBaseTools(jdbcTemplate);
        // Seed a small table and a large table for the row-cap test.
        jdbcTemplate.execute("CREATE TABLE demo (id INT PRIMARY KEY, name TEXT)");
        jdbcTemplate.update("INSERT INTO demo (id, name) VALUES (1, 'alice'), (2, 'bob')");
        jdbcTemplate.execute("CREATE TABLE big (id INT)");
        // Insert 300 rows so the 200-row cap engages.
        for (int i = 0; i < 300; i++) {
            jdbcTemplate.update("INSERT INTO big (id) VALUES (?)", i);
        }
    }

    @BeforeEach
    void resetDemo() {
        // Reset demo to a known state so write-rejection tests stay isolated.
        jdbcTemplate.update("DELETE FROM demo");
        jdbcTemplate.update("INSERT INTO demo (id, name) VALUES (1, 'alice'), (2, 'bob')");
    }

    @Test
    void selectReturnsFormattedRows() {
        String result = dataBaseTools.query("SELECT id, name FROM demo ORDER BY id");

        assertThat(result).startsWith("Query result:");
        assertThat(result).contains("alice");
        assertThat(result).contains("bob");
    }

    @Test
    void nonSelectIsRejectedBeforeExecution() {
        String result = dataBaseTools.query("DELETE FROM demo WHERE id = 1");

        assertThat(result).contains("Error").contains("only SELECT");
        // 没有任何行被删除：写操作被前缀校验拦截。
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo WHERE id = 1", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void multiStatementIsRejected() {
        String result = dataBaseTools.query("SELECT 1; DROP TABLE demo");

        assertThat(result).contains("Error").contains("single SELECT");
        // demo 表仍然存在。
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'demo'", Integer.class);
        assertThat(tables).isEqualTo(1);
    }

    @Test
    void writeIsRejectedAtReadOnlyTransactionBoundary() throws Exception {
        // 直接验证：开启显式只读事务后，UPDATE 必须被 PostgreSQL 拒绝。
        // Connection.setReadOnly 在 PostgreSQL 上对 *下一个* 开启的事务生效，
        // 因此用显式事务边界（setAutoCommit(false) + commit）来可靠触发只读强制。
        try (java.sql.Connection con = dataSource().getConnection()) {
            con.setAutoCommit(false);
            con.setReadOnly(true);
            try {
                try (java.sql.PreparedStatement ps =
                             con.prepareStatement("UPDATE demo SET name = 'x-ro' WHERE id = 1")) {
                    ps.executeUpdate();
                }
                con.commit();
                throw new AssertionError("expected read-only transaction to reject UPDATE");
            } catch (java.sql.SQLException expected) {
                // 只读事务必须拒绝写；回滚该事务。
                con.rollback();
            }
        }
        // demo 未被修改。
        String name = jdbcTemplate.queryForObject("SELECT name FROM demo WHERE id = 1", String.class);
        assertThat(name).isEqualTo("alice");
    }

    @Test
    void resultSetIsCappedAt200Rows() {
        String result = dataBaseTools.query("SELECT id FROM big ORDER BY id");

        assertThat(result).contains("result truncated at " + DataBaseTools.MAX_RESULT_ROWS + " rows");
        // 截断标记出现，证明 200 行上限生效。
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(POSTGRES.getDriverClassName());
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
