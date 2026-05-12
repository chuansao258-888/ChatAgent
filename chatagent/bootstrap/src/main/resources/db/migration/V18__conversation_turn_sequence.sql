ALTER TABLE chat_session
    ADD COLUMN IF NOT EXISTS next_turn_seq BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS last_completed_turn_seq BIGINT NOT NULL DEFAULT 0;

ALTER TABLE chat_message
    ADD COLUMN IF NOT EXISTS turn_seq BIGINT,
    ADD COLUMN IF NOT EXISTS turn_completed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_chat_message_session_turn_seq_order
    ON chat_message(session_id, turn_seq);

CREATE INDEX IF NOT EXISTS idx_chat_message_session_turn_completed
    ON chat_message(session_id, turn_completed, turn_seq)
    WHERE role = 'USER';
