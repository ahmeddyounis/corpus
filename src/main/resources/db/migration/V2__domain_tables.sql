CREATE TABLE users (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username   varchar(100) NOT NULL UNIQUE,
    password   varchar(100) NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE documents (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    filename     varchar(512) NOT NULL,
    content_type varchar(255),
    size_bytes   bigint NOT NULL DEFAULT 0,
    status       varchar(20) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')),
    error        text,
    chunk_count  int NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_documents_user ON documents (user_id);

CREATE TABLE conversations (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title      varchar(255),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_conversations_user ON conversations (user_id);

-- Spring AI JDBC chat memory schema, copied verbatim from
-- spring-ai-model-chat-memory-repository-jdbc 2.0.0 (schema-postgresql.sql).
-- The module's own schema initializer is disabled in application.yml.
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp" TIMESTAMP NOT NULL,
    sequence_id BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
    ON SPRING_AI_CHAT_MEMORY (conversation_id, "timestamp");

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX
    ON SPRING_AI_CHAT_MEMORY (conversation_id, sequence_id);
