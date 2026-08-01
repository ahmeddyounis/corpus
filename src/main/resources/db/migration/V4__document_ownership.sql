-- Ingestion ownership: lets the startup sweep distinguish "this instance's
-- interrupted work" from "another live instance's in-flight work", so a rolling
-- deploy can never fail a document another replica is still processing.
ALTER TABLE documents
    ADD COLUMN owner_instance varchar(64),
    ADD COLUMN claimed_at     timestamptz NOT NULL DEFAULT now();

CREATE INDEX idx_documents_inflight ON documents (status, claimed_at)
    WHERE status IN ('PENDING', 'PROCESSING');

-- Collapse any pre-existing duplicates before the unique index below. Keep the
-- newest READY row per (user_id, filename), else the newest row, and drop the
-- losing rows' chunks from the vector store first so nothing is left orphaned.
WITH ranked AS (
    SELECT id,
           row_number() OVER (PARTITION BY user_id, filename
                              ORDER BY (status = 'READY') DESC, created_at DESC, id) AS rn
    FROM documents
)
DELETE FROM vector_store
WHERE metadata ->> 'document_id' IN (SELECT id::text FROM ranked WHERE rn > 1);

WITH ranked AS (
    SELECT id,
           row_number() OVER (PARTITION BY user_id, filename
                              ORDER BY (status = 'READY') DESC, created_at DESC, id) AS rn
    FROM documents
)
DELETE FROM documents WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

-- Duplicate (user_id, filename) rows made DocumentRepository.findByUserIdAndFilename
-- throw permanently, breaking the document-metadata tool. Make it impossible.
CREATE UNIQUE INDEX uq_documents_user_filename ON documents (user_id, filename);
