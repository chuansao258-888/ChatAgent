-- The Anthropic-compatible Z.AI API used by ChatAgent accepts the bare
-- glm-5.2 model id. The Claude Code-specific glm-5.2[1m] suffix is normalized
-- out of persisted Agent rows while keeping the enlarged context defaults from
-- V28.

UPDATE agent
SET model = 'glm-5.2'
WHERE model = 'glm-5.2[1m]';

DO $$
BEGIN
    IF to_regclass('public.agent_template') IS NOT NULL THEN
        UPDATE agent_template
        SET model = 'glm-5.2'
        WHERE model = 'glm-5.2[1m]';
    END IF;
END $$;
