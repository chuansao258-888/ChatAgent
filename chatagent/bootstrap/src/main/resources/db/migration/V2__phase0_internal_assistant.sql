ALTER TABLE t_user DROP CONSTRAINT IF EXISTS ck_t_user_role;

UPDATE t_user
SET role = LOWER(role)
WHERE role IS NOT NULL
  AND role <> LOWER(role);

ALTER TABLE t_user
    ADD CONSTRAINT ck_t_user_role CHECK (LOWER(role) IN ('admin', 'user'));

INSERT INTO t_user (
    id,
    username,
    password_hash,
    role,
    avatar,
    deleted,
    created_at,
    updated_at
)
SELECT
    '6e8b6d19-4cf7-4f74-9a80-2c7d4f6d6eb5'::uuid,
    '__system_assistant__',
    '$2a$10$7EqJtq98hPqEX7fNZaFWoOHiJ5x2iN6zGyF/F7kh/3Gzdh0dX8G3K',
    'admin',
    NULL,
    TRUE,
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1
    FROM t_user
    WHERE id = '6e8b6d19-4cf7-4f74-9a80-2c7d4f6d6eb5'::uuid
);

UPDATE t_user
SET
    username = '__system_assistant__',
    password_hash = '$2a$10$7EqJtq98hPqEX7fNZaFWoOHiJ5x2iN6zGyF/F7kh/3Gzdh0dX8G3K',
    role = 'admin',
    deleted = TRUE,
    updated_at = NOW()
WHERE id = '6e8b6d19-4cf7-4f74-9a80-2c7d4f6d6eb5'::uuid;

INSERT INTO agent (
    id,
    user_id,
    name,
    description,
    system_prompt,
    model,
    allowed_tools,
    chat_options,
    created_at,
    updated_at
)
SELECT
    '3f9f84f7-2df0-4a5f-9c85-9f2d9b7aaf10'::uuid,
    '6e8b6d19-4cf7-4f74-9a80-2c7d4f6d6eb5'::uuid,
    'ChatAgent',
    'Internal enterprise assistant',
    'You are ChatAgent, the internal enterprise assistant. Give direct, accurate answers and use uploaded session files when they are relevant. Keep responses clear and concise unless the user asks for more detail.',
    'deepseek-chat',
    '[]'::jsonb,
    '{"temperature":0.7,"topP":1.0,"messageLength":10}'::jsonb,
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1
    FROM agent
    WHERE id = '3f9f84f7-2df0-4a5f-9c85-9f2d9b7aaf10'::uuid
);

UPDATE agent
SET
    user_id = '6e8b6d19-4cf7-4f74-9a80-2c7d4f6d6eb5'::uuid,
    name = 'ChatAgent',
    description = 'Internal enterprise assistant',
    system_prompt = 'You are ChatAgent, the internal enterprise assistant. Give direct, accurate answers and use uploaded session files when they are relevant. Keep responses clear and concise unless the user asks for more detail.',
    model = 'deepseek-chat',
    allowed_tools = '[]'::jsonb,
    chat_options = '{"temperature":0.7,"topP":1.0,"messageLength":10}'::jsonb,
    updated_at = NOW()
WHERE id = '3f9f84f7-2df0-4a5f-9c85-9f2d9b7aaf10'::uuid;

UPDATE chat_session cs
SET
    agent_id = '3f9f84f7-2df0-4a5f-9c85-9f2d9b7aaf10'::uuid,
    updated_at = NOW()
WHERE cs.agent_id IS NULL
   OR EXISTS (
       SELECT 1
       FROM agent a
       WHERE a.id = cs.agent_id
         AND a.user_id <> '6e8b6d19-4cf7-4f74-9a80-2c7d4f6d6eb5'::uuid
         AND a.name = 'ChatAgent'
   );
