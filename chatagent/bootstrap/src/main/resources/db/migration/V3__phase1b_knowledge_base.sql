CREATE TABLE knowledge_base (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_by UUID NOT NULL REFERENCES t_user (id),
    name VARCHAR(200) NOT NULL,
    description TEXT,
    visibility VARCHAR(20) NOT NULL DEFAULT 'SHARED',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE knowledge_document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base (id),
    filename VARCHAR(500) NOT NULL,
    original_filename VARCHAR(500),
    mime_type VARCHAR(200),
    size_bytes BIGINT,
    storage_path VARCHAR(1000),
    parse_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    content_hash VARCHAR(128),
    failed_reason TEXT,
    indexed_at TIMESTAMP,
    retry_count INT NOT NULL DEFAULT 0,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE knowledge_chunk (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_document_id UUID NOT NULL REFERENCES knowledge_document (id),
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    token_count INT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT knowledge_chunk_document_chunk_key UNIQUE (knowledge_document_id, chunk_index)
);

CREATE TABLE agent_knowledge_base (
    agent_id UUID NOT NULL REFERENCES agent (id),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base (id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (agent_id, knowledge_base_id)
);

CREATE INDEX idx_knowledge_base_status ON knowledge_base (status);
CREATE INDEX idx_knowledge_document_kb ON knowledge_document (knowledge_base_id);
CREATE INDEX idx_knowledge_document_kb_deleted ON knowledge_document (knowledge_base_id, deleted);
CREATE INDEX idx_knowledge_chunk_document ON knowledge_chunk (knowledge_document_id);
