-- Upgrade stored Agent model IDs to the code-registered provider/model keys.
-- Runtime Agent routing is driven by chat.routing.agent-primary-model and
-- chat.routing.agent-fallback-model, but keeping persisted compatibility data
-- current avoids old rows surfacing retired model names in admin/debug flows.

UPDATE agent
SET model = CASE model
    WHEN 'deepseek-chat' THEN 'deepseek-v4-flash'
    WHEN 'deepseek-reasoner' THEN 'deepseek-v4-pro'
    WHEN 'glm-4.6' THEN 'glm-4.7'
    WHEN 'glm-5.1' THEN 'glm-4.7'
    ELSE model
END
WHERE model IN ('deepseek-chat', 'deepseek-reasoner', 'glm-4.6', 'glm-5.1');
