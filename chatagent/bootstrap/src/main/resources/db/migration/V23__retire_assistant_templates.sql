-- V23: retire the legacy assistant-template (custom-agent) feature.
--
-- The project now ships a single fixed internal assistant; the template catalog,
-- its CRUD endpoints/UI, and the demo personas (Customer Service / IT Ops /
-- Data Analysis) are retired. The internal assistant's system_prompt is cleared
-- so the runtime falls back to the PromptLoader template
-- prompts/agent/default-system-prompt.md (see DefaultAgentRuntimeContextLoader).

-- Drop the template store. No foreign keys reference it, and the only code that
-- queried it (MCP reverse-lookup) no longer does.
DROP TABLE IF EXISTS agent_template;

-- Remove legacy demo agent rows. They are never the active internal assistant
-- (InternalAssistantService.SYSTEM_ASSISTANT_ID = 3f9f84f7) and have no
-- chat_session / agent_knowledge_base references. Idempotent.
DELETE FROM agent
WHERE id IN ('c9b7ec1f-82ed-4ada-89e3-8c757506280f'::uuid,
             '573bcf37-dd86-43ff-8b2b-2789027c1847'::uuid);

-- Clear the system assistant's DB system_prompt so the runtime uses the
-- PromptLoader default instead of a per-agent override.
UPDATE agent
SET system_prompt = '',
    name         = 'ChatAgent'
WHERE id = '3f9f84f7-2df0-4a5f-9c85-9f2d9b7aaf10'::uuid;
