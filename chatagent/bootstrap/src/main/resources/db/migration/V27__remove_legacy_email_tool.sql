-- Remove the retired Mail tool from runtime allowlists.
--
-- The EmailTools / EmailService runtime has been removed. Existing rows may
-- still reference the old backend tool name, so strip it from allowlists and
-- disable TOOL intent nodes that were left with no callable tool after removal.

UPDATE agent
SET allowed_tools = COALESCE(allowed_tools, '[]'::jsonb) - 'emailTool',
    updated_at = CURRENT_TIMESTAMP
WHERE COALESCE(allowed_tools, '[]'::jsonb) ? 'emailTool';

UPDATE intent_node
SET allowed_tools = allowed_tools - 'emailTool',
    enabled = CASE
        WHEN intent_kind = 'TOOL'
             AND jsonb_typeof(allowed_tools - 'emailTool') = 'array'
             AND jsonb_array_length(allowed_tools - 'emailTool') = 0
            THEN FALSE
        ELSE enabled
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE allowed_tools ? 'emailTool';

DO $$
BEGIN
    IF to_regclass('public.agent_template') IS NOT NULL THEN
        UPDATE agent_template
        SET allowed_tools = COALESCE(allowed_tools, '[]'::jsonb) - 'emailTool',
            updated_at = CURRENT_TIMESTAMP
        WHERE COALESCE(allowed_tools, '[]'::jsonb) ? 'emailTool';
    END IF;
END $$;
