ALTER TABLE t_mcp_server
    DROP CONSTRAINT IF EXISTS uq_t_mcp_server_slug;

CREATE UNIQUE INDEX IF NOT EXISTS ux_t_mcp_server_slug_active
    ON t_mcp_server (slug)
    WHERE deleted_at IS NULL;

ALTER TABLE t_mcp_tool_catalog
    DROP CONSTRAINT IF EXISTS uq_t_mcp_tool_catalog_model_name;

ALTER TABLE t_mcp_tool_catalog
    DROP CONSTRAINT IF EXISTS uq_t_mcp_tool_catalog_server_remote;

CREATE UNIQUE INDEX IF NOT EXISTS ux_t_mcp_tool_catalog_model_name_active
    ON t_mcp_tool_catalog (exposed_model_name)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_t_mcp_tool_catalog_server_remote_active
    ON t_mcp_tool_catalog (server_id, remote_original_name)
    WHERE deleted_at IS NULL;
