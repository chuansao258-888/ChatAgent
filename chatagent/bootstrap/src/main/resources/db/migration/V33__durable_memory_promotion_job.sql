-- Durable L3 promotion handoff owned by the memory subsystem.
-- L2 compaction inserts one row in the same transaction as segment/watermark commit.

CREATE TABLE memory_promotion_job (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES t_user(id),
    session_id VARCHAR(64) NOT NULL,
    seq_start_no BIGINT NOT NULL,
    seq_end_no BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processing_started_at TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_memory_promotion_job_status
        CHECK (status IN ('pending', 'processing', 'completed', 'failed')),
    CONSTRAINT uk_memory_promotion_job_range
        UNIQUE (session_id, seq_start_no, seq_end_no)
);

CREATE INDEX idx_memory_promotion_job_claim
    ON memory_promotion_job(status, next_attempt_at, created_at);

CREATE INDEX idx_memory_promotion_job_processing
    ON memory_promotion_job(status, processing_started_at);

-- Preserve only unfinished legacy work. Completed ranges need no new execution.
INSERT INTO memory_promotion_job (
    user_id, session_id, seq_start_no, seq_end_no, status, attempts,
    next_attempt_at, last_error, created_at, updated_at
)
SELECT user_id, session_id, seq_start_no, seq_end_no, 'pending', 0,
       CURRENT_TIMESTAMP, error_message, created_at, CURRENT_TIMESTAMP
FROM memory_extraction_log
WHERE status IN ('processing', 'failed')
ON CONFLICT ON CONSTRAINT uk_memory_promotion_job_range DO NOTHING;
