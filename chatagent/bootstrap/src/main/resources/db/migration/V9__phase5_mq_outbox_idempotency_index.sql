CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_mq_outbox_sent_idempotency
    ON t_mq_outbox (((headers ->> 'x-idempotency-key')), event_type)
    WHERE status = 'SENT'
      AND (headers ->> 'x-idempotency-key') IS NOT NULL;
