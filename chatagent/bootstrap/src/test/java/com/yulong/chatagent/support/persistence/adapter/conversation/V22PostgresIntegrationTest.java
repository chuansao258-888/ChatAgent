package com.yulong.chatagent.support.persistence.adapter.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import com.yulong.chatagent.support.dto.ChatSessionSummarySegmentDTO;
import com.yulong.chatagent.support.persistence.mapper.ChatSessionSummaryMapper;
import com.yulong.chatagent.support.persistence.mapper.ChatSessionSummarySegmentMapper;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL-backed smoke test that validates V22 migration + MyBatis XML + PostgreSQL
 * work together for summary and segment CRUD paths.
 *
 * <p>This catches issues that mock-based tests cannot: NOT NULL constraint violations,
 * JSONB cast failures, ON CONFLICT behavior, and column/DTO mismatches.
 */
@Testcontainers(disabledWithoutDocker = true)
class V22PostgresIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
        createSchema(dataSource);
        sqlSessionFactory = buildSqlSessionFactory(dataSource);
    }

    @BeforeEach
    void resetData() {
        jdbcTemplate.execute("TRUNCATE TABLE chat_session_summary_segment, chat_session_summary, chat_session, t_user RESTART IDENTITY CASCADE");
        jdbcTemplate.update("""
                INSERT INTO t_user (id, username, password_hash, role)
                VALUES (CAST(? AS uuid), ?, ?, 'user')
                """, "00000000-0000-0000-0000-000000000001", "test-user", "hash");
        jdbcTemplate.update("""
                INSERT INTO chat_session (id, user_id, next_turn_seq, last_completed_turn_seq)
                VALUES (?, CAST('00000000-0000-0000-0000-000000000001' AS uuid), 1, 0)
                """, "session-1");
    }

    // ── Summary save / read / delete ──────────────────────────────────

    @Test
    void shouldInsertAndReadSummaryWithoutStructuredSummaryJson() {
        // Simulates IncrementalSummarizer building a DTO without structuredSummaryJson
        ChatSessionSummaryDTO dto = ChatSessionSummaryDTO.builder()
                .sessionId("session-1")
                .summarizedUntilSeqNo(8L)
                .synopsis("User discussed reimbursement")
                .anchoredEntities(Map.of("dates", List.of("2026-03-28")))
                .build();

        boolean saved = saveOrUpdate(dto);
        assertThat(saved).isTrue();

        ChatSessionSummaryDTO loaded = findBySessionId("session-1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getSummarizedUntilSeqNo()).isEqualTo(8L);
        assertThat(loaded.getSynopsis()).isEqualTo("User discussed reimbursement");
        assertThat(loaded.getAnchoredEntities()).containsEntry("dates", List.of("2026-03-28"));
        assertThat(loaded.getSegmentCount()).isEqualTo(0);
        assertThat(loaded.getConsecutiveFailures()).isEqualTo(0);
        assertThat(loaded.getVersion()).isEqualTo(0);
    }

    @Test
    void shouldUpdateSummaryWithOptimisticLock() {
        saveOrUpdate(ChatSessionSummaryDTO.builder()
                .sessionId("session-1")
                .summarizedUntilSeqNo(8L)
                .synopsis("First")
                .anchoredEntities(Map.of())
                .build());

        boolean updated = saveOrUpdate(ChatSessionSummaryDTO.builder()
                .sessionId("session-1")
                .summarizedUntilSeqNo(16L)
                .synopsis("Second")
                .anchoredEntities(Map.of())
                .build());

        assertThat(updated).isTrue();
        ChatSessionSummaryDTO loaded = findBySessionId("session-1");
        assertThat(loaded.getSummarizedUntilSeqNo()).isEqualTo(16L);
        assertThat(loaded.getSynopsis()).isEqualTo("Second");
        assertThat(loaded.getVersion()).isEqualTo(1);
    }

    @Test
    void shouldPreserveNonZeroCountersWhenCallerOmitsThem() {
        // Insert with explicit nonzero segment/failure counters
        ChatSessionSummaryDTO initial = ChatSessionSummaryDTO.builder()
                .sessionId("session-1")
                .summarizedUntilSeqNo(8L)
                .synopsis("First")
                .anchoredEntities(Map.of())
                .segmentCount(2)
                .consecutiveFailures(1)
                .build();
        saveOrUpdate(initial);

        // Update only synopsis/watermark, without setting counters
        boolean updated = saveOrUpdate(ChatSessionSummaryDTO.builder()
                .sessionId("session-1")
                .summarizedUntilSeqNo(16L)
                .synopsis("Second")
                .anchoredEntities(Map.of())
                .build());

        assertThat(updated).isTrue();
        ChatSessionSummaryDTO loaded = findBySessionId("session-1");
        assertThat(loaded.getSummarizedUntilSeqNo()).isEqualTo(16L);
        assertThat(loaded.getSynopsis()).isEqualTo("Second");
        assertThat(loaded.getSegmentCount()).isEqualTo(2);
        assertThat(loaded.getConsecutiveFailures()).isEqualTo(1);
        assertThat(loaded.getVersion()).isEqualTo(1);
    }

    @Test
    void shouldDeleteSummaryBySessionId() {
        saveOrUpdate(ChatSessionSummaryDTO.builder()
                .sessionId("session-1")
                .summarizedUntilSeqNo(8L)
                .synopsis("Will be deleted")
                .anchoredEntities(Map.of())
                .build());

        boolean deleted = deleteSummary("session-1");
        assertThat(deleted).isTrue();
        assertThat(findBySessionId("session-1")).isNull();
    }

    // ── Segment insert / idempotent duplicate / read / delete ──────────

    @Test
    void shouldInsertSegmentAndReadBack() {
        ChatSessionSummarySegmentDTO segment = ChatSessionSummarySegmentDTO.builder()
                .sessionId("session-1")
                .seqStartNo(1L)
                .seqEndNo(8L)
                .turnCount(3)
                .sourceTokenEstimate(400)
                .segmentSummary("User discussed reimbursement.")
                .anchoredEntities(Map.of("orderIds", List.of("AB-1234")))
                .build();

        boolean inserted = insertSegment(segment);
        assertThat(inserted).isTrue();

        List<ChatSessionSummarySegmentDTO> active = findActiveSegmentsOrdered("session-1");
        assertThat(active).hasSize(1);
        ChatSessionSummarySegmentDTO loaded = active.get(0);
        assertThat(loaded.getSessionId()).isEqualTo("session-1");
        assertThat(loaded.getSeqStartNo()).isEqualTo(1L);
        assertThat(loaded.getSeqEndNo()).isEqualTo(8L);
        assertThat(loaded.getSegmentSummary()).isEqualTo("User discussed reimbursement.");
        assertThat(loaded.getAnchoredEntities()).containsEntry("orderIds", List.of("AB-1234"));
        assertThat(loaded.getStatus()).isEqualTo("active");
        assertThat(loaded.getId()).isNotNull();
    }

    @Test
    void shouldTreatDuplicateSegmentInsertAsIdempotentNoOp() {
        ChatSessionSummarySegmentDTO segment = ChatSessionSummarySegmentDTO.builder()
                .sessionId("session-1")
                .seqStartNo(1L)
                .seqEndNo(8L)
                .turnCount(3)
                .sourceTokenEstimate(400)
                .segmentSummary("First insert")
                .anchoredEntities(Map.of())
                .build();

        boolean first = insertSegment(segment);
        assertThat(first).isTrue();

        // Same range again — ON CONFLICT DO NOTHING
        ChatSessionSummarySegmentDTO duplicate = ChatSessionSummarySegmentDTO.builder()
                .sessionId("session-1")
                .seqStartNo(1L)
                .seqEndNo(8L)
                .turnCount(3)
                .sourceTokenEstimate(400)
                .segmentSummary("Should not replace")
                .anchoredEntities(Map.of())
                .build();
        boolean second = insertSegment(duplicate);
        assertThat(second).isFalse();

        // Original content preserved
        List<ChatSessionSummarySegmentDTO> active = findActiveSegmentsOrdered("session-1");
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getSegmentSummary()).isEqualTo("First insert");
    }

    @Test
    void shouldDeleteSegmentsBySessionId() {
        ChatSessionSummarySegmentDTO segment = ChatSessionSummarySegmentDTO.builder()
                .sessionId("session-1")
                .seqStartNo(1L)
                .seqEndNo(8L)
                .turnCount(1)
                .sourceTokenEstimate(100)
                .segmentSummary("To be deleted")
                .anchoredEntities(Map.of())
                .build();
        insertSegment(segment);

        boolean deleted = deleteSegments("session-1");
        assertThat(deleted).isTrue();
        assertThat(findActiveSegmentsOrdered("session-1")).isEmpty();
    }

    // ── Infrastructure ──────────────────────────────────────────────────

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(POSTGRES.getDriverClassName());
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    private static void createSchema(DataSource dataSource) throws Exception {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        // Minimal chat_session schema needed by V22 FKs
        jdbcTemplate.execute("""
                CREATE TABLE t_user (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    username VARCHAR(255) NOT NULL,
                    password_hash VARCHAR(255) NOT NULL,
                    role VARCHAR(50) NOT NULL DEFAULT 'user',
                    avatar VARCHAR(500),
                    deleted BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
                    CONSTRAINT uk_t_user_username UNIQUE (username),
                    CONSTRAINT ck_t_user_role CHECK (role IN ('admin', 'user'))
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE chat_session (
                    id VARCHAR(64) PRIMARY KEY,
                    user_id UUID NOT NULL REFERENCES t_user(id),
                    agent_id UUID,
                    title TEXT,
                    metadata JSONB,
                    next_turn_seq BIGINT NOT NULL DEFAULT 1,
                    last_completed_turn_seq BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP DEFAULT NOW(),
                    updated_at TIMESTAMP DEFAULT NOW()
                )
                """);
        // V22 migration
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V22__memory_compaction_v2.sql"));
        }
    }

    private static SqlSessionFactory buildSqlSessionFactory(DataSource dataSource) throws Exception {
        Environment environment = new Environment("postgres-test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        try (InputStream summaryXml = Resources.getResourceAsStream("mapper/ChatSessionSummaryMapper.xml")) {
            new XMLMapperBuilder(summaryXml, configuration, "mapper/ChatSessionSummaryMapper.xml", configuration.getSqlFragments()).parse();
        }
        try (InputStream segmentXml = Resources.getResourceAsStream("mapper/ChatSessionSummarySegmentMapper.xml")) {
            new XMLMapperBuilder(segmentXml, configuration, "mapper/ChatSessionSummarySegmentMapper.xml", configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    // ── Repository delegates (open session per call) ──────────────────

    private static boolean saveOrUpdate(ChatSessionSummaryDTO dto) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ChatSessionSummaryMapper mapper = session.getMapper(ChatSessionSummaryMapper.class);
            MyBatisChatSessionSummaryRepository repo = new MyBatisChatSessionSummaryRepository(mapper, OBJECT_MAPPER);
            return repo.saveOrUpdate(dto);
        }
    }

    private static ChatSessionSummaryDTO findBySessionId(String sessionId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ChatSessionSummaryMapper mapper = session.getMapper(ChatSessionSummaryMapper.class);
            MyBatisChatSessionSummaryRepository repo = new MyBatisChatSessionSummaryRepository(mapper, OBJECT_MAPPER);
            return repo.findBySessionId(sessionId);
        }
    }

    private static boolean deleteSummary(String sessionId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ChatSessionSummaryMapper mapper = session.getMapper(ChatSessionSummaryMapper.class);
            MyBatisChatSessionSummaryRepository repo = new MyBatisChatSessionSummaryRepository(mapper, OBJECT_MAPPER);
            return repo.deleteBySessionId(sessionId);
        }
    }

    private static boolean insertSegment(ChatSessionSummarySegmentDTO dto) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ChatSessionSummarySegmentMapper mapper = session.getMapper(ChatSessionSummarySegmentMapper.class);
            MyBatisChatSessionSummarySegmentRepository repo = new MyBatisChatSessionSummarySegmentRepository(mapper, OBJECT_MAPPER);
            return repo.insert(dto);
        }
    }

    private static List<ChatSessionSummarySegmentDTO> findActiveSegmentsOrdered(String sessionId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ChatSessionSummarySegmentMapper mapper = session.getMapper(ChatSessionSummarySegmentMapper.class);
            MyBatisChatSessionSummarySegmentRepository repo = new MyBatisChatSessionSummarySegmentRepository(mapper, OBJECT_MAPPER);
            return repo.findActiveBySessionIdOrdered(sessionId);
        }
    }

    private static boolean deleteSegments(String sessionId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ChatSessionSummarySegmentMapper mapper = session.getMapper(ChatSessionSummarySegmentMapper.class);
            MyBatisChatSessionSummarySegmentRepository repo = new MyBatisChatSessionSummarySegmentRepository(mapper, OBJECT_MAPPER);
            return repo.deleteBySessionId(sessionId);
        }
    }
}
