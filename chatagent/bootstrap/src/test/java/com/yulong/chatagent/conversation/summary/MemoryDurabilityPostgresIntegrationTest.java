package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.conversation.port.ChatSessionSummarySegmentRepository;
import com.yulong.chatagent.memory.port.MemoryPromotionJobRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import com.yulong.chatagent.support.dto.ChatSessionSummarySegmentDTO;
import com.yulong.chatagent.support.dto.MemoryPromotionJobDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MemoryDurabilityPostgresIntegrationTest {
    private static final String USER_ID = "00000000-0000-0000-0000-000000000011";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"));

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUpSchema() throws Exception {
        DriverManagerDataSource configured = new DriverManagerDataSource();
        configured.setDriverClassName(POSTGRES.getDriverClassName());
        configured.setUrl(POSTGRES.getJdbcUrl());
        configured.setUsername(POSTGRES.getUsername());
        configured.setPassword(POSTGRES.getPassword());
        dataSource = configured;
        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("CREATE TABLE t_user (id UUID PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE chat_session (
                    id VARCHAR(64) PRIMARY KEY,
                    user_id UUID NOT NULL REFERENCES t_user(id)
                )
                """);
        runMigration("V20__l3_long_term_memory.sql");
        runMigration("V22__memory_compaction_v2.sql");

        jdbc.update("INSERT INTO t_user(id) VALUES (CAST(? AS uuid))", USER_ID);
        jdbc.update("INSERT INTO chat_session(id,user_id) VALUES (?,CAST(? AS uuid))",
                "legacy-session", USER_ID);
        jdbc.update("""
                INSERT INTO memory_extraction_log(
                    user_id, session_id, seq_start_no, seq_end_no, status, error_message)
                VALUES (CAST(? AS uuid), ?, 5, 8, 'failed', 'legacy failure')
                """, USER_ID, "legacy-session");

        runMigration("V33__durable_memory_promotion_job.sql");
        runMigration("V34__retire_legacy_memory_promotion.sql");
    }

    @Test
    void shouldBackfillUnfinishedLegacyRangeAndDropOldTable() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM memory_promotion_job
                WHERE session_id='legacy-session' AND seq_start_no=5 AND seq_end_no=8
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT to_regclass('memory_extraction_log')", String.class))
                .isNull();
    }

    @Test
    void shouldCommitSegmentWatermarkAndJobTogether() {
        String sessionId = "commit-session";
        insertSession(sessionId);
        jdbc.update("""
                INSERT INTO chat_session_summary(session_id,summarized_until_seq_no,synopsis)
                VALUES (?,4,'before')
                """, sessionId);

        MemoryCompactionCommitService service = service(false);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(ignored -> service.commit(
                4L, segment(sessionId), summary(sessionId, 8L)));

        assertThat(count("chat_session_summary_segment", sessionId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT summarized_until_seq_no FROM chat_session_summary WHERE session_id=?",
                Long.class, sessionId)).isEqualTo(8L);
        assertThat(count("memory_promotion_job", sessionId)).isEqualTo(1L);
    }

    @Test
    void shouldRollbackSegmentAndJobWhenSummaryWriteFails() {
        String sessionId = "rollback-session";
        insertSession(sessionId);
        MemoryCompactionCommitService service = service(true);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> service.commit(
                0L, segment(sessionId), summary(sessionId, 8L))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("chat_session_summary_segment", sessionId)).isZero();
        assertThat(count("chat_session_summary", sessionId)).isZero();
        assertThat(count("memory_promotion_job", sessionId)).isZero();
    }

    private static MemoryCompactionCommitService service(boolean failSummaryWrite) {
        return new MemoryCompactionCommitService(
                new JdbcSummaryRepository(jdbc, failSummaryWrite),
                new JdbcSegmentRepository(jdbc),
                new JdbcJobRepository(jdbc),
                true);
    }

    private static ChatSessionSummarySegmentDTO segment(String sessionId) {
        return ChatSessionSummarySegmentDTO.builder()
                .sessionId(sessionId).seqStartNo(5L).seqEndNo(8L)
                .turnCount(1).sourceTokenEstimate(10).segmentSummary("summary").build();
    }

    private static ChatSessionSummaryDTO summary(String sessionId, long watermark) {
        return ChatSessionSummaryDTO.builder()
                .sessionId(sessionId).summarizedUntilSeqNo(watermark)
                .synopsis("after").segmentCount(1).consecutiveFailures(0).build();
    }

    private static void insertSession(String sessionId) {
        jdbc.update("INSERT INTO chat_session(id,user_id) VALUES (?,CAST(? AS uuid))",
                sessionId, USER_ID);
    }

    private static long count(String table, String sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE session_id=?",
                Long.class, sessionId);
    }

    private static void runMigration(String name) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + name));
        }
    }

    private static final class JdbcSummaryRepository implements ChatSessionSummaryRepository {
        private final JdbcTemplate jdbc;
        private final boolean failWrite;

        private JdbcSummaryRepository(JdbcTemplate jdbc, boolean failWrite) {
            this.jdbc = jdbc;
            this.failWrite = failWrite;
        }

        @Override
        public ChatSessionSummaryDTO findBySessionId(String sessionId) {
            List<ChatSessionSummaryDTO> rows = jdbc.query(
                    "SELECT session_id,summarized_until_seq_no,segment_count FROM chat_session_summary WHERE session_id=?",
                    (rs, row) -> ChatSessionSummaryDTO.builder()
                            .sessionId(rs.getString(1)).summarizedUntilSeqNo(rs.getLong(2))
                            .segmentCount(rs.getInt(3)).build(), sessionId);
            return rows.isEmpty() ? null : rows.get(0);
        }

        @Override
        public boolean saveOrUpdate(ChatSessionSummaryDTO value) {
            if (failWrite) {
                throw new IllegalStateException("forced summary failure");
            }
            return jdbc.update("""
                    INSERT INTO chat_session_summary(
                        session_id,summarized_until_seq_no,synopsis,segment_count,consecutive_failures)
                    VALUES (?,?,?,?,?)
                    ON CONFLICT (session_id) DO UPDATE SET
                        summarized_until_seq_no=EXCLUDED.summarized_until_seq_no,
                        synopsis=EXCLUDED.synopsis,
                        segment_count=EXCLUDED.segment_count
                    """, value.getSessionId(), value.getSummarizedUntilSeqNo(), value.getSynopsis(),
                    value.getSegmentCount(), value.getConsecutiveFailures()) > 0;
        }

        @Override
        public boolean deleteBySessionId(String sessionId) {
            return jdbc.update("DELETE FROM chat_session_summary WHERE session_id=?", sessionId) > 0;
        }
    }

    private static final class JdbcSegmentRepository implements ChatSessionSummarySegmentRepository {
        private final JdbcTemplate jdbc;

        private JdbcSegmentRepository(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public boolean insert(ChatSessionSummarySegmentDTO value) {
            return jdbc.update("""
                    INSERT INTO chat_session_summary_segment(
                        session_id,seq_start_no,seq_end_no,turn_count,source_token_estimate,segment_summary)
                    VALUES (?,?,?,?,?,?)
                    ON CONFLICT ON CONSTRAINT uk_chat_session_summary_segment_range DO NOTHING
                    """, value.getSessionId(), value.getSeqStartNo(), value.getSeqEndNo(),
                    value.getTurnCount(), value.getSourceTokenEstimate(), value.getSegmentSummary()) > 0;
        }

        @Override public List<ChatSessionSummarySegmentDTO> findActiveBySessionId(String sessionId) { return List.of(); }
        @Override public List<ChatSessionSummarySegmentDTO> findActiveBySessionIdOrdered(String sessionId) { return List.of(); }
        @Override public boolean deleteBySessionId(String sessionId) { return false; }
    }

    private static final class JdbcJobRepository implements MemoryPromotionJobRepository {
        private final JdbcTemplate jdbc;

        private JdbcJobRepository(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public boolean insertPendingForSession(String sessionId, long seqStartNo, long seqEndNo) {
            return jdbc.update("""
                    INSERT INTO memory_promotion_job(user_id,session_id,seq_start_no,seq_end_no)
                    SELECT user_id,id,?,? FROM chat_session WHERE id=?
                    ON CONFLICT ON CONSTRAINT uk_memory_promotion_job_range DO NOTHING
                    """, seqStartNo, seqEndNo, sessionId) > 0;
        }

        @Override public MemoryPromotionJobDTO claimNextDue(LocalDateTime now) { throw new UnsupportedOperationException(); }
        @Override public boolean markCompleted(String id) { throw new UnsupportedOperationException(); }
        @Override public boolean markRetry(String id, LocalDateTime nextAttemptAt, String lastError) { throw new UnsupportedOperationException(); }
        @Override public boolean markFailed(String id, String lastError) { throw new UnsupportedOperationException(); }
        @Override public int reclaimStale(LocalDateTime processingStartedBefore) { throw new UnsupportedOperationException(); }
        @Override public long countBacklog() { return 0; }
    }
}
