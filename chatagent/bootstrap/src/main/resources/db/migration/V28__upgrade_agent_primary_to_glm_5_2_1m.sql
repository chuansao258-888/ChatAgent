-- Upgrade stored Agent defaults for the GLM-5.2 1M primary model and larger
-- runtime context defaults. Runtime routing still comes from application.yaml;
-- this keeps persisted admin/debug rows and newly seeded templates aligned.

UPDATE agent
SET model = 'glm-5.2[1m]'
WHERE model = 'glm-4.7';

UPDATE agent
SET chat_options = jsonb_set(
        jsonb_set(
            COALESCE(chat_options, '{}'::jsonb),
            '{messageLength}',
            '80'::jsonb,
            true
        ),
        '{tokenBudget}',
        '128000'::jsonb,
        true
    )
WHERE CASE
          WHEN (chat_options ->> 'messageLength') ~ '^[0-9]+$'
              THEN (chat_options ->> 'messageLength')::int
          ELSE 0
      END < 80
   OR CASE
          WHEN (chat_options ->> 'tokenBudget') ~ '^[0-9]+$'
              THEN (chat_options ->> 'tokenBudget')::int
          ELSE 0
      END < 128000;

DO $$
BEGIN
    IF to_regclass('public.agent_template') IS NOT NULL THEN
        UPDATE agent_template
        SET model = 'glm-5.2[1m]'
        WHERE model = 'glm-4.7';

        UPDATE agent_template
        SET chat_options = jsonb_set(
                jsonb_set(
                    COALESCE(chat_options, '{}'::jsonb),
                    '{messageLength}',
                    '80'::jsonb,
                    true
                ),
                '{tokenBudget}',
                '128000'::jsonb,
                true
            )
        WHERE CASE
                  WHEN (chat_options ->> 'messageLength') ~ '^[0-9]+$'
                      THEN (chat_options ->> 'messageLength')::int
                  ELSE 0
              END < 80
           OR CASE
                  WHEN (chat_options ->> 'tokenBudget') ~ '^[0-9]+$'
                      THEN (chat_options ->> 'tokenBudget')::int
                  ELSE 0
              END < 128000;
    END IF;
END $$;
