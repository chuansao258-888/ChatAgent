-- Step 0: dedupe historical SENT rows before adding the unique expression index.
WITH ranked_sent AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY (headers ->> 'x-idempotency-key'), event_type
               ORDER BY created_at DESC, id DESC
           ) AS rn
    FROM t_mq_outbox
    WHERE status = 'SENT'
      AND (headers ->> 'x-idempotency-key') IS NOT NULL
)
DELETE FROM t_mq_outbox target
USING ranked_sent ranked
WHERE target.id = ranked.id
  AND ranked.rn > 1;
