CREATE TABLE IF NOT EXISTS t_mcp_alert_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    server_id UUID,
    server_slug VARCHAR(32),
    tool_name VARCHAR(64),
    alert_type VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    summary VARCHAR(256) NOT NULL,
    details_json TEXT,
    resolved_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_t_mcp_alert_event_server FOREIGN KEY (server_id) REFERENCES t_mcp_server(id),
    CONSTRAINT ck_t_mcp_alert_event_type CHECK (alert_type IN ('SERVER_FAILED', 'SCHEMA_DRIFT', 'UNRESOLVED_REFERENCE')),
    CONSTRAINT ck_t_mcp_alert_event_severity CHECK (severity IN ('WARNING', 'ERROR')),
    CONSTRAINT ck_t_mcp_alert_event_status CHECK (status IN ('OPEN', 'RESOLVED'))
);

CREATE INDEX IF NOT EXISTS idx_t_mcp_alert_event_status_created
    ON t_mcp_alert_event(status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_t_mcp_alert_event_server_type_status
    ON t_mcp_alert_event(server_id, alert_type, status);
