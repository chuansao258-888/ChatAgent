ALTER TABLE chat_message
    ADD COLUMN IF NOT EXISTS seq_no BIGSERIAL;

ALTER TABLE chat_message
    ADD COLUMN IF NOT EXISTS turn_id VARCHAR(36);

UPDATE chat_message
SET turn_id = id::text
WHERE turn_id IS NULL OR turn_id = '';

ALTER TABLE chat_message
    ALTER COLUMN turn_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_message_seq_no
    ON chat_message(seq_no);

CREATE INDEX IF NOT EXISTS idx_chat_message_session_seq
    ON chat_message(session_id, seq_no);

CREATE INDEX IF NOT EXISTS idx_chat_message_session_turn_seq
    ON chat_message(session_id, turn_id, seq_no);

CREATE TABLE IF NOT EXISTS chat_session_summary (
    session_id UUID PRIMARY KEY REFERENCES chat_session(id) ON DELETE CASCADE,
    last_seq_no BIGINT NOT NULL DEFAULT 0,
    summary TEXT NOT NULL DEFAULT '',
    anchored_entities JSONB NOT NULL DEFAULT '{}'::jsonb,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
