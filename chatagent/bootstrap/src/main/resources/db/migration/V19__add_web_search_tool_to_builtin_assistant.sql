UPDATE agent
SET
    allowed_tools = CASE
        WHEN allowed_tools IS NULL THEN '["webSearchTool"]'::jsonb
        ELSE allowed_tools || '["webSearchTool"]'::jsonb
    END,
    updated_at = NOW()
WHERE id = '3f9f84f7-2df0-4a5f-9c85-9f2d9b7aaf10'::uuid
  AND (
      allowed_tools IS NULL
      OR (
          jsonb_typeof(allowed_tools) = 'array'
          AND NOT (allowed_tools ? 'webSearchTool')
      )
  );
