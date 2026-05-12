CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE t_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'user',
    avatar VARCHAR(500),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_t_user_username UNIQUE (username),
    CONSTRAINT ck_t_user_role CHECK (role IN ('admin', 'user'))
);

CREATE TABLE user_profile (
    user_id UUID PRIMARY KEY,
    summary TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_profile_user_id_fkey FOREIGN KEY (user_id) REFERENCES t_user (id)
);

CREATE TABLE agent (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    system_prompt TEXT,
    model TEXT,
    allowed_tools JSONB,
    chat_options JSONB,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT fk_agent_user FOREIGN KEY (user_id) REFERENCES t_user (id)
);

CREATE TABLE chat_session (
    id VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL,
    agent_id UUID,
    title TEXT,
    metadata JSONB,
    next_turn_seq BIGINT NOT NULL DEFAULT 1,
    last_completed_turn_seq BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT fk_chat_session_user FOREIGN KEY (user_id) REFERENCES t_user (id),
    CONSTRAINT chat_session_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent (id)
);

CREATE TABLE chat_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id VARCHAR(64) NOT NULL,
    turn_seq BIGINT,
    role TEXT NOT NULL,
    content TEXT,
    metadata JSONB,
    turn_completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT chat_message_session_id_fkey FOREIGN KEY (session_id) REFERENCES chat_session (id)
);

CREATE TABLE chat_session_file (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id VARCHAR(64) NOT NULL,
    filename VARCHAR(500) NOT NULL,
    original_filename VARCHAR(500) NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    storage_path VARCHAR(1000) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'UPLOADED',
    parse_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chat_session_file_session_id_fkey FOREIGN KEY (session_id) REFERENCES chat_session (id),
    CONSTRAINT chat_session_file_size_bytes_check CHECK (size_bytes >= 0)
);

CREATE TABLE file_chunk (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_file_id UUID NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    token_count INT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT file_chunk_session_file_id_fkey FOREIGN KEY (session_file_id) REFERENCES chat_session_file (id),
    CONSTRAINT file_chunk_session_file_id_chunk_index_key UNIQUE (session_file_id, chunk_index)
);
