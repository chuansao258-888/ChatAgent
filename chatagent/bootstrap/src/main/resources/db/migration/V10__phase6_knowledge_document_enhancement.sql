CREATE TABLE knowledge_document_enhancement (
    knowledge_document_id UUID PRIMARY KEY REFERENCES knowledge_document (id) ON DELETE CASCADE,
    enhancer_cache_key VARCHAR(128),
    keywords JSONB NOT NULL DEFAULT '[]'::jsonb,
    questions JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_knowledge_document_enhancement_cache_key
    ON knowledge_document_enhancement (enhancer_cache_key);
