package com.yulong.chatagent.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.yulong.chatagent.agent.runtime.CurrentToolDeadlineHolder;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;

/**
 * 可选数据库查询工具。
 * <p>
 * 该工具只允许执行 SELECT，避免模型通过工具做写入、删除或 DDL 操作。
 * <p>
 * ARRB Phase 1（ARRB-DEC-018）：在把它归类为 READ_ONLY 之前，必须在数据库事务边界
 * 证明只读语义。这里的强制是：只读事务 + 语句查询超时 + 200 行上限 + 拒绝非 SELECT。
 */
@Component
@Slf4j
public class DataBaseTools implements Tool {

    /** 单次查询返回的行上限，防止模型拉取超大结果集。 */
    static final int MAX_RESULT_ROWS = 200;

    /** 单条语句的查询超时（秒），防止长查询拖垮 run 截止时间。 */
    static final int QUERY_TIMEOUT_SECONDS = 10;

    private final JdbcTemplate jdbcTemplate;

    public DataBaseTools(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getName() {
        return "dataBaseTool";
    }

    @Override
    public String getDescription() {
        return "Execute read-only SQL queries against PostgreSQL and return formatted results.";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @Override
    public ToolEffectClass effectClass() {
        // ARRB-DEC-018：已用只读事务 + 查询超时 + 200 行上限在数据库边界证明只读语义，
        // 因此可以归类为 READ_ONLY（否则只能保持 UNKNOWN）。
        return ToolEffectClass.READ_ONLY;
    }

    @Override
    public DeadlineMode deadlineMode() {
        return DeadlineMode.ENFORCED;
    }

    /**
     * 执行一条只读 SELECT，并返回 Markdown 风格的表格文本。
     *
     * @param sql 待执行 SQL
     * @return 格式化查询结果或错误信息
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "databaseQuery",
            description = "Execute a read-only SQL SELECT query against PostgreSQL and return structured results. Write operations are not allowed."
    )
    public String query(String sql) {
        try {
            CurrentToolDeadlineHolder.remainingMillisOrDefault(30_000L);
            // 运行时再次做只读校验，不能只依赖工具描述约束模型行为。
            // 拒绝非 SELECT 起首，也拒绝分号拼接的多语句（避免 SELECT; DROP ... 类注入）。
            String trimmed = sql == null ? "" : sql.trim();
            String upper = trimmed.toUpperCase();
            if (!upper.startsWith("SELECT")) {
                log.warn("Rejected non-SELECT database tool query.");
                return "Error: only SELECT statements are supported.";
            }
            if (trimmed.contains(";")) {
                log.warn("Rejected multi-statement database tool query.");
                return "Error: only a single SELECT statement is supported.";
            }

            // ARRB-DEC-018：在只读事务里执行，带语句查询超时与 200 行上限，
            // 在数据库边界证明只读语义而不是仅靠 startsWith("SELECT")。
            List<String> rows = executeReadOnly(sql);

            int dataRowCount = rows.size() - 2;
            if (rows.size() > 2 && rows.get(rows.size() - 1).contains("(no data)")) {
                dataRowCount = 0;
            }

            log.info("SQL query executed successfully, returned {} data rows", dataRowCount);
            return "Query result:\n" + String.join("\n", rows);
        } catch (Exception e) {
            log.error("Database query failed: exception={}", e.getClass().getSimpleName());
            log.debug("Database query failure details", e);
            return "Error: database query failed.";
        }
    }

    /**
     * 在只读事务边界执行 SELECT：连接设为只读、语句设查询超时、结果集封顶 MAX_RESULT_ROWS 行。
     * 这样即使模型绕过 SELECT 前缀校验，PostgreSQL 也会在只读事务里拒绝任何写操作。
     */
    private List<String> executeReadOnly(String sql) throws Exception {
        return jdbcTemplate.execute((java.sql.Connection con) -> {
            // 进入前保存原只读状态，结束后还原，避免污染连接池里的连接。
            boolean originalReadOnly = con.isReadOnly();
            con.setReadOnly(true);
            try (java.sql.PreparedStatement ps = con.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                long remainingMillis = CurrentToolDeadlineHolder.remainingMillisOrDefault(30_000L);
                int remainingSeconds = (int) Math.max(1L, Math.min(
                        QUERY_TIMEOUT_SECONDS, (remainingMillis + 999L) / 1_000L));
                ps.setQueryTimeout(remainingSeconds);
                try (ResultSet rs = ps.executeQuery()) {
                    return renderRows(rs);
                }
            } finally {
                con.setReadOnly(originalReadOnly);
            }
        });
    }

    private static List<String> renderRows(ResultSet rs) throws java.sql.SQLException {
        List<String> resultRows = new ArrayList<>();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        if (columnCount == 0) {
            resultRows.add("Query returned no columns.");
            return resultRows;
        }

        List<String> columnNames = new ArrayList<>();
        List<Integer> columnWidths = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            String columnName = metaData.getColumnName(i);
            columnNames.add(columnName);
            columnWidths.add(columnName.length());
        }

        List<List<String>> dataRows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (dataRows.size() >= MAX_RESULT_ROWS) {
                // 超过 200 行就停止读取，避免拉超大结果集。
                truncated = true;
                break;
            }
            List<String> rowData = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                Object value = rs.getObject(i);
                String valueStr = value == null ? "NULL" : value.toString();
                rowData.add(valueStr);
                int currentWidth = columnWidths.get(i - 1);
                if (valueStr.length() > currentWidth) {
                    columnWidths.set(i - 1, valueStr.length());
                }
            }
            dataRows.add(rowData);
        }

        StringBuilder header = new StringBuilder();
        header.append("| ");
        for (int i = 0; i < columnCount; i++) {
            String columnName = columnNames.get(i);
            int width = columnWidths.get(i);
            header.append(String.format("%-" + width + "s", columnName)).append(" | ");
        }
        resultRows.add(header.toString());

        StringBuilder separator = new StringBuilder();
        separator.append("|");
        for (int i = 0; i < columnCount; i++) {
            int width = columnWidths.get(i);
            separator.append("-".repeat(width + 2)).append("|");
        }
        resultRows.add(separator.toString());

        if (dataRows.isEmpty()) {
            StringBuilder emptyRow = new StringBuilder();
            emptyRow.append("| ");
            int totalWidth = columnWidths.stream().mapToInt(w -> w + 3).sum() - 1;
            emptyRow.append(String.format("%-" + (totalWidth - 2) + "s", "(no data)"));
            emptyRow.append(" |");
            resultRows.add(emptyRow.toString());
        } else {
            for (List<String> rowData : dataRows) {
                StringBuilder row = new StringBuilder();
                row.append("| ");
                for (int i = 0; i < columnCount; i++) {
                    String value = rowData.get(i);
                    int width = columnWidths.get(i);
                    row.append(String.format("%-" + width + "s", value)).append(" | ");
                }
                resultRows.add(row.toString());
            }
            if (truncated) {
                resultRows.add("(result truncated at " + MAX_RESULT_ROWS + " rows)");
            }
        }

        return resultRows;
    }
}
