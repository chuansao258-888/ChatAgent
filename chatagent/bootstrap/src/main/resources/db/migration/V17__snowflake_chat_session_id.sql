ALTER TABLE chat_message
    DROP CONSTRAINT IF EXISTS chat_message_session_id_fkey;

ALTER TABLE chat_session_file
    DROP CONSTRAINT IF EXISTS chat_session_file_session_id_fkey;

ALTER TABLE chat_session_summary
    DROP CONSTRAINT IF EXISTS chat_session_summary_session_id_fkey;

ALTER TABLE t_chat_turn_metric
    DROP CONSTRAINT IF EXISTS t_chat_turn_metric_session_id_fkey;

ALTER TABLE chat_session
    ALTER COLUMN id DROP DEFAULT,
    ALTER COLUMN id TYPE VARCHAR(64) USING id::text;

ALTER TABLE chat_message
    ALTER COLUMN session_id TYPE VARCHAR(64) USING session_id::text;

ALTER TABLE chat_session_file
    ALTER COLUMN session_id TYPE VARCHAR(64) USING session_id::text;

ALTER TABLE chat_session_summary
    ALTER COLUMN session_id TYPE VARCHAR(64) USING session_id::text;

ALTER TABLE t_chat_turn_metric
    ALTER COLUMN session_id TYPE VARCHAR(64) USING session_id::text;

ALTER TABLE chat_message
    ADD CONSTRAINT chat_message_session_id_fkey
        FOREIGN KEY (session_id) REFERENCES chat_session(id);

ALTER TABLE chat_session_file
    ADD CONSTRAINT chat_session_file_session_id_fkey
        FOREIGN KEY (session_id) REFERENCES chat_session(id);

ALTER TABLE chat_session_summary
    ADD CONSTRAINT chat_session_summary_session_id_fkey
        FOREIGN KEY (session_id) REFERENCES chat_session(id) ON DELETE CASCADE;

ALTER TABLE t_chat_turn_metric
    ADD CONSTRAINT t_chat_turn_metric_session_id_fkey
        FOREIGN KEY (session_id) REFERENCES chat_session(id) ON DELETE CASCADE;
