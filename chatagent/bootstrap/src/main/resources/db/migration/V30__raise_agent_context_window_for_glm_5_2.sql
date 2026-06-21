-- Raise persisted Agent chat-window defaults after standardizing on the
-- GLM-5.2 long-context primary. Runtime routing still comes from
-- application.yaml; this keeps existing admin/debug rows aligned with the
-- enlarged application defaults without changing custom high values.

UPDATE agent
SET chat_options = jsonb_set(
        jsonb_set(
            COALESCE(chat_options, '{}'::jsonb),
            '{messageLength}',
            '120'::jsonb,
            true
        ),
        '{tokenBudget}',
        '256000'::jsonb,
        true
    )
WHERE CASE
          WHEN (chat_options ->> 'messageLength') ~ '^[0-9]+$'
              THEN (chat_options ->> 'messageLength')::int
          ELSE 0
      END < 120
   OR CASE
          WHEN (chat_options ->> 'tokenBudget') ~ '^[0-9]+$'
              THEN (chat_options ->> 'tokenBudget')::int
          ELSE 0
      END < 256000;

DO $$
BEGIN
    IF to_regclass('public.agent_template') IS NOT NULL THEN
        UPDATE agent_template
        SET chat_options = jsonb_set(
                jsonb_set(
                    COALESCE(chat_options, '{}'::jsonb),
                    '{messageLength}',
                    '120'::jsonb,
                    true
                ),
                '{tokenBudget}',
                '256000'::jsonb,
                true
            )
        WHERE CASE
                  WHEN (chat_options ->> 'messageLength') ~ '^[0-9]+$'
                      THEN (chat_options ->> 'messageLength')::int
                  ELSE 0
              END < 120
           OR CASE
                  WHEN (chat_options ->> 'tokenBudget') ~ '^[0-9]+$'
                      THEN (chat_options ->> 'tokenBudget')::int
                  ELSE 0
              END < 256000;
    END IF;
END $$;
