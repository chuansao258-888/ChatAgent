ALTER TABLE t_mcp_tool_catalog
    ADD COLUMN IF NOT EXISTS output_schema_json TEXT,
    ADD COLUMN IF NOT EXISTS annotations_json TEXT,
    ADD COLUMN IF NOT EXISTS descriptor_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS effect_policy VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS policy_version BIGINT NOT NULL DEFAULT 0;

UPDATE t_mcp_tool_catalog
SET descriptor_hash = schema_hash
WHERE descriptor_hash IS NULL;

ALTER TABLE t_mcp_tool_catalog
    ADD CONSTRAINT ck_t_mcp_tool_catalog_effect_policy
        CHECK (effect_policy IN ('READ_ONLY', 'IDEMPOTENT', 'NON_IDEMPOTENT', 'UNKNOWN'));
