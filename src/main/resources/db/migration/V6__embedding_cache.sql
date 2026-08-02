-- Content-addressed embedding cache.
--
-- Embeddings are a pure function of (text, provider, model, dimension), so they
-- are cacheable across users in a way document content never is. The namespace
-- column carries provider:model:dimension, which makes a model or dimension
-- change a cache *miss* rather than a silent correctness bug: entries written by
-- a different model can never be read back, because the key does not match.
--
-- The row stores a hash and a vector, never the source text. Nothing here can
-- reconstruct another user's document, which is what makes crossing the
-- project's per-user partitioning safe for this one derived, non-returnable
-- artifact. See ADR 0011.
CREATE TABLE embedding_cache (
    namespace     varchar(200) NOT NULL,
    content_hash  char(64)     NOT NULL,
    -- real[] rather than vector(n): this table is only ever read by exact key,
    -- never by similarity, so it needs no pgvector operators and no dimension
    -- constraint. A dimension change then rebuilds the namespace instead of
    -- failing the migration.
    embedding     real[]       NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    last_used_at  timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (namespace, content_hash)
);

-- Supports evicting the coldest entries when the cache is trimmed.
CREATE INDEX idx_embedding_cache_last_used ON embedding_cache (namespace, last_used_at);
