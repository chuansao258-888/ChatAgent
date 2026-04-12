-- V16: Prompt centralization — clear the default agent's DB prompt so the runtime
-- reads from the centralized classpath:prompts/agent/default-system-prompt.md instead.
-- The PromptLoader fallback path in DefaultAgentRuntimeContextLoader.buildSystemPrompt()
-- is activated when system_prompt is NULL.

UPDATE agent
SET system_prompt = NULL
WHERE name = 'ChatAgent'
  AND system_prompt IS NOT NULL;
