package com.yulong.chatagent.support.persistence.adapter.conversation;

import com.yulong.chatagent.support.persistence.mapper.ChatSessionMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
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
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ChatSessionTurnSequencePostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
    );

    private static JdbcTemplate jdbcTemplate;
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        DataSource dataSource = dataSource();
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
        sqlSessionFactory = buildSqlSessionFactory(dataSource);
    }

    @BeforeEach
    void resetData() {
        jdbcTemplate.execute("TRUNCATE TABLE chat_message, chat_session RESTART IDENTITY CASCADE");
        jdbcTemplate.update("""
                INSERT INTO chat_session (id, next_turn_seq, last_completed_turn_seq)
                VALUES ('session-1', 4, 0)
                """);
    }

    @Test
    void shouldAdvanceAcrossContiguousCompletedLowercaseUserTurns() {
        insertTurn(1, true);
        insertTurn(2, true);
        insertTurn(3, true);

        assertThat(advanceCompletedTurnSeq()).isEqualTo(3L);
    }

    @Test
    void shouldStopAtGapAndAdvanceAfterGapCompletes() {
        insertTurn(1, true);
        insertTurn(2, false);
        insertTurn(3, true);

        assertThat(advanceCompletedTurnSeq()).isEqualTo(1L);

        jdbcTemplate.update("""
                UPDATE chat_message
                   SET turn_completed = TRUE
                 WHERE session_id = 'session-1'
                   AND turn_seq = 2
                """);

        assertThat(advanceCompletedTurnSeq()).isEqualTo(3L);
    }

    private void insertTurn(long turnSeq, boolean completed) {
        jdbcTemplate.update("""
                INSERT INTO chat_message (session_id, turn_id, turn_seq, role, turn_completed)
                VALUES ('session-1', ?, ?, 'user', ?),
                       ('session-1', ?, ?, 'assistant', ?)
                """,
                "turn-" + turnSeq, turnSeq, completed,
                "turn-" + turnSeq, turnSeq, completed);
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private static void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE chat_session (
                    id VARCHAR(64) PRIMARY KEY,
                    next_turn_seq BIGINT NOT NULL DEFAULT 1,
                    last_completed_turn_seq BIGINT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP DEFAULT NOW()
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE chat_message (
                    id BIGSERIAL PRIMARY KEY,
                    session_id VARCHAR(64) NOT NULL REFERENCES chat_session(id),
                    turn_id VARCHAR(64) NOT NULL,
                    turn_seq BIGINT,
                    role TEXT NOT NULL,
                    turn_completed BOOLEAN NOT NULL DEFAULT FALSE
                )
                """);
    }

    private static SqlSessionFactory buildSqlSessionFactory(DataSource dataSource) throws Exception {
        Environment environment = new Environment("postgres-test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        try (InputStream mapperXml = Resources.getResourceAsStream("mapper/ChatSessionMapper.xml")) {
            new XMLMapperBuilder(
                    mapperXml,
                    configuration,
                    "mapper/ChatSessionMapper.xml",
                    configuration.getSqlFragments()
            ).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static Long advanceCompletedTurnSeq() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(ChatSessionMapper.class).advanceCompletedTurnSeq("session-1");
        }
    }
}
