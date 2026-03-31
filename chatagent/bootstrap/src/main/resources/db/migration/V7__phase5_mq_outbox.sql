-- Transactional Outbox table for reliable MQ publishing (Stage 4A).
-- Status flow: PENDING -> CLAIMED -> SENT (terminal) or FAILED (terminal after max retries).

CREATE TABLE t_mq_outbox (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(100)  NOT NULL,
    exchange        VARCHAR(200)  NOT NULL,
    routing_key     VARCHAR(200)  NOT NULL,
    payload         JSONB         NOT NULL DEFAULT '{}'::jsonb,
    headers         JSONB         NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    next_retry_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error      TEXT,
    claimed_at      TIMESTAMP,
    claimed_by      VARCHAR(200),
    retry_count     INT           NOT NULL DEFAULT 0,
    version         INT           NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Poller query: WHERE status IN ('PENDING','CLAIMED') AND next_retry_at <= now AND retry_count < max
CREATE INDEX idx_t_mq_outbox_poll ON t_mq_outbox (status, next_retry_at);

-- Cleanup job: WHERE status = 'SENT' AND created_at < cutoff
CREATE INDEX idx_t_mq_outbox_cleanup ON t_mq_outbox (status, created_at);
