-- Per-user semantic response cache.
--
-- Unlike the embedding cache, answers are NOT shareable: they are derived from
-- one user's documents and quote them directly. Isolation here is structural,
-- not conventional - the FK cascades with the user and every read carries a
-- mandatory user_id predicate. See ADR 0012.

-- Monotonic per-user stamp, bumped whenever the corpus changes. Cached answers
-- carry the version they were computed under, so an upload or delete makes every
-- prior entry unreachable without touching a row. Entries written concurrently
-- with an ingest are born stale and simply never match again.
CREATE TABLE corpus_version (
    user_id    uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    version    bigint      NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE response_cache (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- provider:model, so switching models cannot serve an answer the current
    -- model never produced.
    model_key      varchar(200) NOT NULL,
    -- sha256 of the normalized question: the exact-match phase costs no
    -- embedding call at all.
    question_hash  char(64)     NOT NULL,
    question       text         NOT NULL,
    answer         text         NOT NULL,
    citations      jsonb        NOT NULL,
    embedding      vector(${vector_dimension}) NOT NULL,
    corpus_version bigint       NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    hit_count      int          NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_response_cache_exact
    ON response_cache (user_id, model_key, corpus_version, question_hash);

-- Deliberately NOT an HNSW index. Approximate indexes are applied before the
-- user_id filter, so a filtered ANN search silently returns fewer rows than it
-- should - which for a cache means unpredictable misses, and for a bug means a
-- lookup that quietly stops being isolated. An exact scan inside one user's
-- bounded partition (a few hundred rows) is both faster and correct.
CREATE INDEX idx_response_cache_partition
    ON response_cache (user_id, model_key, corpus_version, created_at DESC);
