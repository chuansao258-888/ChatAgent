CREATE TABLE IF NOT EXISTS t_chat_turn_metric (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id VARCHAR(64) NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES t_user(id) ON DELETE CASCADE,
    turn_id VARCHAR(64) NOT NULL,
    agent_id UUID REFERENCES agent(id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL,
    error_type VARCHAR(50),
    duration_ms BIGINT NOT NULL,
    knowledge_hit BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_t_chat_turn_metric_status CHECK (status IN ('SUCCESS', 'ERROR'))
);

CREATE INDEX IF NOT EXISTS idx_chat_turn_metric_created_at
    ON t_chat_turn_metric(created_at);

CREATE INDEX IF NOT EXISTS idx_chat_turn_metric_status
    ON t_chat_turn_metric(status);

CREATE INDEX IF NOT EXISTS idx_chat_turn_metric_session_turn
    ON t_chat_turn_metric(session_id, turn_id);
