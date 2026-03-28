ALTER TABLE agent
    ADD COLUMN IF NOT EXISTS active_intent_version INT;

CREATE TABLE IF NOT EXISTS intent_node (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id UUID NOT NULL REFERENCES agent(id),
    parent_id UUID REFERENCES intent_node(id),
    version INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    node_level VARCHAR(32) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    examples JSONB NOT NULL DEFAULT '[]'::jsonb,
    intent_kind VARCHAR(32),
    scope_policy VARCHAR(32),
    allowed_tools JSONB NOT NULL DEFAULT '[]'::jsonb,
    system_prompt_override TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_intent_node_agent_version
    ON intent_node(agent_id, version, sort_order);

CREATE INDEX IF NOT EXISTS idx_intent_node_parent
    ON intent_node(parent_id, sort_order);

CREATE TABLE IF NOT EXISTS intent_knowledge_base (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    intent_node_id UUID NOT NULL REFERENCES intent_node(id) ON DELETE CASCADE,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_intent_knowledge_base_binding
    ON intent_knowledge_base(intent_node_id, knowledge_base_id);

