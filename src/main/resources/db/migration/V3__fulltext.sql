-- Keyword leg of hybrid retrieval: a generated tsvector over chunk content,
-- kept in the same table so RRF can fuse both result lists on vector_store.id.
ALTER TABLE vector_store
    ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;

CREATE INDEX idx_vector_store_tsv ON vector_store USING gin (content_tsv);
