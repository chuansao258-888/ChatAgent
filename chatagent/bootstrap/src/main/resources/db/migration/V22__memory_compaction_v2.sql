-- V22: Memory Compaction V2 — reset L2 summary schema and add segment table.
--
-- Old L2 summary data is intentionally discarded (not backfilled).
-- Rollback after this migration may require database restore or a recreation migration.
-- See docs/adr/0001-memory-compaction-v2-l2-schema-reset.md

DROP TABLE IF EXISTS chat_session_summary_segment;
DROP TABLE IF EXISTS chat_session_summary;

CREATE TABLE chat_session_summary (
    session_id             VARCHAR(64) PRIMARY KEY REFERENCES chat_session(id) ON DELETE CASCADE,
    summarized_until_seq_no BIGINT NOT NULL DEFAULT 0,
    synopsis               TEXT NOT NULL DEFAULT '',
    structured_summary     JSONB NOT NULL DEFAULT '{}'::jsonb,
    anchored_entities      JSONB NOT NULL DEFAULT '{}'::jsonb,
    segment_count          INT NOT NULL DEFAULT 0,
    consecutive_failures   INT NOT NULL DEFAULT 0,
    failed_start_seq_no    BIGINT,
    failed_end_seq_no      BIGINT,
    last_failure_class     VARCHAR(128),
    next_retry_at          TIMESTAMP,
    version                INT NOT NULL DEFAULT 0,
    created_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE chat_session_summary_segment (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id            VARCHAR(64) NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    seq_start_no          BIGINT NOT NULL,
    seq_end_no            BIGINT NOT NULL,
    turn_count            INT NOT NULL DEFAULT 0,
    source_token_estimate INT NOT NULL DEFAULT 0,
    segment_summary       TEXT NOT NULL DEFAULT '',
    structured_summary    JSONB NOT NULL DEFAULT '{}'::jsonb,
    anchored_entities     JSONB NOT NULL DEFAULT '{}'::jsonb,
    status                VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_chat_session_summary_segment_status CHECK (status IN ('active', 'failed')),
    CONSTRAINT uk_chat_session_summary_segment_range UNIQUE (session_id, seq_start_no, seq_end_no)
);

CREATE INDEX idx_chat_session_summary_segment_session_seq
    ON chat_session_summary_segment(session_id, seq_end_no);
