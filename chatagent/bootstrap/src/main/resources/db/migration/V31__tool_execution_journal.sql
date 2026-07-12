-- ARRB Phase 1: sanitized relational tool-execution journal.
-- Provides the cross-process unique compare-and-set boundary that chat_message JSONB cannot.
-- See plan ARRB-DEC-010 and ARRB-AC-009.
--
-- Invariants:
--   * execution_key is UNIQUE: at most one dispatch per (approval | turn+tool+canonical args).
--   * state transitions use expected old-state predicates (CAS); the external callback is
--     never inside the transaction that writes the terminal state.
--   * the table stores only sanitized identity + hashes, never raw arguments/results bodies.
--   * assistant_message_id / response_message_id use non-blocking delete semantics so normal
--     conversation deletion is never prevented, while the hashed key/state remains.

CREATE TABLE t_tool_execution_journal (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Unique dispatch key: approvalId for approved effects, or
    -- turnId + toolName + SHA-256(canonical args) for read-only calls.
    execution_key VARCHAR(128) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    turn_id VARCHAR(64),
    approval_id VARCHAR(128),
    -- Stable reference to the persisted assistant tool-call message (nullable, non-blocking delete).
    assistant_message_id UUID,
    -- Model-facing tool-call id after ToolCallPreflight normalization.
    tool_call_id VARCHAR(512),
    tool_name VARCHAR(256) NOT NULL,
    -- SHA-256 of canonical arguments; never the raw arguments themselves.
    argument_hash VARCHAR(64) NOT NULL,
    effect_class VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    attempt INT NOT NULL DEFAULT 1,
    state VARCHAR(24) NOT NULL DEFAULT 'PREPARED',
    -- Low-cardinality safe error code only (e.g. OUTCOME_UNKNOWN, FAILED_KNOWN).
    safe_error_code VARCHAR(64),
    -- Stable reference to the paired tool-response message (nullable, non-blocking delete).
    response_message_id UUID,
    -- SHA-256 of the normalized paired response, for integrity checks; never the raw body.
    response_hash VARCHAR(64),
    dispatched_at TIMESTAMP,
    call_deadline_ms BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_tool_execution_journal_effect_class
        CHECK (effect_class IN ('READ_ONLY', 'IDEMPOTENT', 'NON_IDEMPOTENT', 'UNKNOWN')),
    CONSTRAINT ck_tool_execution_journal_state
        CHECK (state IN ('PREPARED', 'BLOCKED', 'DISPATCHING', 'SUCCEEDED',
                         'FAILED_KNOWN', 'OUTCOME_UNKNOWN')),
    CONSTRAINT uk_tool_execution_journal_key UNIQUE (execution_key),
    CONSTRAINT fk_tool_execution_journal_session
        FOREIGN KEY (session_id) REFERENCES chat_session (id),
    CONSTRAINT fk_tool_execution_journal_assistant_msg
        FOREIGN KEY (assistant_message_id) REFERENCES chat_message (id) ON DELETE SET NULL,
    CONSTRAINT fk_tool_execution_journal_response_msg
        FOREIGN KEY (response_message_id) REFERENCES chat_message (id) ON DELETE SET NULL
);

CREATE INDEX idx_tool_execution_journal_session_turn
    ON t_tool_execution_journal (session_id, turn_id);

CREATE INDEX idx_tool_execution_journal_state
    ON t_tool_execution_journal (state, updated_at);

CREATE INDEX idx_tool_execution_journal_approval
    ON t_tool_execution_journal (approval_id)
    WHERE approval_id IS NOT NULL;
