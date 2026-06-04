-- L3 Long-Term User Memory
-- Replaces user_profile.summary with atomic memory items.
-- The deprecated user_profile table is dropped by V21; runtime no longer reads or writes it.

CREATE TABLE memory_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    source JSONB NOT NULL DEFAULT '{}'::jsonb,
    content_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    index_status VARCHAR(16) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT memory_item_user_id_fkey FOREIGN KEY (user_id) REFERENCES t_user (id),
    CONSTRAINT ck_memory_item_type CHECK (type IN ('preference', 'fact')),
    CONSTRAINT ck_memory_item_status CHECK (status IN ('active', 'archived')),
    CONSTRAINT ck_memory_item_index_status CHECK (index_status IN ('pending', 'indexed', 'failed')),
    CONSTRAINT uk_memory_item_hash UNIQUE (user_id, type, content_hash)
);

CREATE INDEX idx_memory_item_user_status_type
    ON memory_item (user_id, status, type);

CREATE INDEX idx_memory_item_index_status
    ON memory_item (index_status);

-- Extraction idempotency log: prevents reprocessing the same L2 batch range.
CREATE TABLE memory_extraction_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    seq_start_no BIGINT NOT NULL,
    seq_end_no BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT memory_extraction_log_user_id_fkey FOREIGN KEY (user_id) REFERENCES t_user (id),
    CONSTRAINT ck_memory_extraction_log_status CHECK (status IN ('processing', 'completed', 'failed')),
    CONSTRAINT uk_memory_extraction_log_range UNIQUE (session_id, seq_start_no, seq_end_no)
);

-- DEPRECATED: user_profile table is no longer used by runtime L3 memory.
-- Dropped by V21__drop_deprecated_user_profile.sql.
