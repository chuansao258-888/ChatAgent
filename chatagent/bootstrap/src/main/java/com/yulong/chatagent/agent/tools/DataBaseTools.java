package com.yulong.chatagent.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;

/**
 * 可选数据库查询工具。
 * <p>
 * 该工具只允许执行 SELECT，避免模型通过工具做写入、删除或 DDL 操作。
 */
@Component
@Slf4j
public class DataBaseTools implements Tool {

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
            // 运行时再次做只读校验，不能只依赖工具描述约束模型行为。
            String trimmedSql = sql.trim().toUpperCase();
            if (!trimmedSql.startsWith("SELECT")) {
                log.warn("Rejected non-SELECT database tool query.");
                return "Error: only SELECT statements are supported.";
            }

            List<String> rows = jdbcTemplate.query(sql, (ResultSet rs) -> {
                List<String> resultRows = new ArrayList<>();
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                if (columnCount == 0) {
                    resultRows.add("Query returned no columns.");
                    return resultRows;
                }

                // 先扫描列名和数据宽度，再组装等宽表格，方便模型后续读取结果。
                List<String> columnNames = new ArrayList<>();
                List<Integer> columnWidths = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metaData.getColumnName(i);
                    columnNames.add(columnName);
                    columnWidths.add(columnName.length());
                }

                List<List<String>> dataRows = new ArrayList<>();
                while (rs.next()) {
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
                }

                return resultRows;
            });

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
}
