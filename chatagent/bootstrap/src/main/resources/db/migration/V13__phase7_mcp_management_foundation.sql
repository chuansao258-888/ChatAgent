CREATE TABLE IF NOT EXISTS t_mcp_server (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    protocol VARCHAR(16) NOT NULL,
    auth_type VARCHAR(16) NOT NULL DEFAULT 'NONE',
    endpoint_url VARCHAR(1024) NOT NULL,
    encrypted_credentials TEXT,
    credential_key_version VARCHAR(32),
    status VARCHAR(16) NOT NULL DEFAULT 'DISABLED',
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_tested_at TIMESTAMP,
    last_initialized_at TIMESTAMP,
    last_sync_at TIMESTAMP,
    last_error_code VARCHAR(64),
    last_error_message VARCHAR(512),
    deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_t_mcp_server_slug UNIQUE (slug),
    CONSTRAINT ck_t_mcp_server_protocol CHECK (protocol IN ('HTTP', 'SSE')),
    CONSTRAINT ck_t_mcp_server_auth_type CHECK (auth_type IN ('NONE', 'API_KEY', 'BEARER_TOKEN', 'OAUTH2_CLIENT')),
    CONSTRAINT ck_t_mcp_server_status CHECK (status IN ('ACTIVE', 'DISABLED', 'FAILED', 'STALE'))
);

CREATE INDEX IF NOT EXISTS idx_t_mcp_server_status_deleted
    ON t_mcp_server(status, deleted_at);

CREATE TABLE IF NOT EXISTS t_mcp_tool_catalog (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    server_id UUID NOT NULL,
    remote_original_name VARCHAR(128) NOT NULL,
    tool_description VARCHAR(512),
    exposed_model_name VARCHAR(64) NOT NULL,
    schema_json TEXT NOT NULL,
    schema_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DISABLED',
    deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_synced_at TIMESTAMP,
    CONSTRAINT fk_t_mcp_tool_catalog_server FOREIGN KEY (server_id) REFERENCES t_mcp_server(id),
    CONSTRAINT uq_t_mcp_tool_catalog_model_name UNIQUE (exposed_model_name),
    CONSTRAINT uq_t_mcp_tool_catalog_server_remote UNIQUE (server_id, remote_original_name),
    CONSTRAINT ck_t_mcp_tool_catalog_status CHECK (status IN ('ENABLED', 'DISABLED', 'STALE'))
);

CREATE INDEX IF NOT EXISTS idx_t_mcp_tool_catalog_server_status_deleted
    ON t_mcp_tool_catalog(server_id, status, deleted_at);

CREATE INDEX IF NOT EXISTS idx_agent_allowed_tools_gin
    ON agent USING GIN (allowed_tools);

CREATE INDEX IF NOT EXISTS idx_intent_node_allowed_tools_gin
    ON intent_node USING GIN (allowed_tools);

CREATE INDEX IF NOT EXISTS idx_agent_template_allowed_tools_gin
    ON agent_template USING GIN (allowed_tools);

CREATE INDEX IF NOT EXISTS idx_agent_template_intent_tree_gin
    ON agent_template USING GIN (intent_tree);
