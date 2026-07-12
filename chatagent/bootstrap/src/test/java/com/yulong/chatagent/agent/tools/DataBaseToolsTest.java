package com.yulong.chatagent.agent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    void shouldRejectMultiStatementSqlWithoutEchoingIt() {
        // ARRB Phase 1：分号拼接的多语句直接拒绝，避免 SELECT; DROP ... 注入。
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataBaseTools tool = new DataBaseTools(jdbcTemplate);

        String output = tool.query("SELECT 1; DROP TABLE secret_table");

        assertThat(output).contains("Error").contains("single SELECT");
        assertThat(output).doesNotContain("secret_table");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void shouldUseJdbcTemplateOnlyForReadOnlySelect() throws Exception {
        // query() 现在在只读事务里执行：execute(ConnectionCallback) 内部设置只读 + 查询超时，
        // 再把 ResultSet 渲染成表格。这里用 mock connection 驱动该回调，验证只读路径仍然工作。
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Connection con = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);

        when(con.isReadOnly()).thenReturn(false);
        when(con.prepareStatement(eq("SELECT 1 AS answer"), any(int.class), any(int.class)))
                .thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("answer");
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(1);

        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                .thenAnswer(inv -> ((ConnectionCallback<?>) inv.getArgument(0)).doInConnection(con));

        DataBaseTools tool = new DataBaseTools(jdbcTemplate);

        String output = tool.query("SELECT 1 AS answer");

        assertThat(output).contains("Query result:").contains("answer");
        // 只读连接被设置并还原。
        org.mockito.Mockito.verify(con).setReadOnly(true);
        org.mockito.Mockito.verify(con).setReadOnly(false);
        // 语句查询超时被设置（ARRB-DEC-018）。
        org.mockito.Mockito.verify(ps).setQueryTimeout(DataBaseTools.QUERY_TIMEOUT_SECONDS);
    }

    @Test
    void shouldReturnSafeErrorWhenReadQueryFailsWithoutEchoingSql(CapturedOutput output) {
        // execute(ConnectionCallback) 内部抛出的任何异常都被 query() 兜底成 safe 错误信息，
        // 不回显 SQL、不回显敏感表名/令牌，只在低基数日志里记录异常类名。
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
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

    @Test
    void shouldCapResultSetAtMaxRows() throws Exception {
        // 200 行上限：构造一个超过 200 行的结果集，验证渲染在 200 行停止并打截断标记。
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Connection con = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);

        when(con.isReadOnly()).thenReturn(false);
        when(con.prepareStatement(any(String.class), any(int.class), any(int.class))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        // 让 rs.next() 永远返回 true，迫使 row cap 触发。
        when(rs.next()).thenReturn(true);
        when(rs.getObject(1)).thenReturn(1);

        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                .thenAnswer(inv -> ((ConnectionCallback<?>) inv.getArgument(0)).doInConnection(con));

        DataBaseTools tool = new DataBaseTools(jdbcTemplate);
        String result = tool.query("SELECT id FROM big_table");

        assertThat(result).contains("result truncated at " + DataBaseTools.MAX_RESULT_ROWS + " rows");
    }
}
