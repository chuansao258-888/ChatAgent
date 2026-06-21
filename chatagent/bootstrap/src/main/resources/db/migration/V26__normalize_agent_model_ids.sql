-- Normalize the stable IDs produced by historical V24 to the current
-- code-registered Agent provider/model keys. V25 remains immutable because it
-- may already have been applied before this compatibility gap was discovered.

UPDATE agent
SET model = CASE model
    WHEN 'chat-fast' THEN 'deepseek-v4-flash'
    WHEN 'chat-reasoning' THEN 'deepseek-v4-pro'
    WHEN 'chat-default' THEN 'glm-4.7'
    ELSE model
END
WHERE model IN ('chat-fast', 'chat-reasoning', 'chat-default');
