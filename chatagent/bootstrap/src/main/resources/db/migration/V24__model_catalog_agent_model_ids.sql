-- Map legacy provider model IDs to stable model catalog IDs.
-- The agent.model column is already TEXT; this migration preserves existing rows
-- while removing the old provider-ID persistence contract.

UPDATE agent
SET model = CASE model
    WHEN 'deepseek-chat' THEN 'chat-fast'
    WHEN 'deepseek-reasoner' THEN 'chat-reasoning'
    WHEN 'glm-4.6' THEN 'chat-default'
    WHEN 'glm-5.1' THEN 'chat-default'
    ELSE model
END
WHERE model IN ('deepseek-chat', 'deepseek-reasoner', 'glm-4.6', 'glm-5.1');
