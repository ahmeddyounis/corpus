# pgvector in practice: HNSW tuning, distance metrics, and metadata filtering

Corpus stores embeddings in PostgreSQL using the pgvector extension. Chunks
live in a single `vector_store` table: a UUID primary key, the chunk text, a
JSONB metadata column, and an `embedding vector(N)` column whose dimension is
fixed by the active embedding model.

## HNSW index parameters

Approximate nearest-neighbor search uses an HNSW (Hierarchical Navigable Small
World) index declared with `USING hnsw (embedding vector_cosine_ops)`. Two
build-time parameters control the graph. The parameter `m` (Corpus uses 16)
sets how many bidirectional links each node keeps: higher `m` builds a denser
graph that improves recall at the cost of memory and build time. The parameter
`ef_construction` (Corpus uses 64) sets the candidate-list size while the
index is being built: larger values explore more neighbors per insertion and
yield a higher-quality graph, again trading build speed.

At query time, `hnsw.ef_search` controls the search-time candidate list. The
default of 40 is fine for top-k retrieval around k=20; raising it improves
recall on large corpora at a latency cost. These knobs matter once the table
holds hundreds of thousands of vectors — at demo scale the index is nearly
irrelevant, but declaring it from day one means behavior does not change when
data grows.

## Distance metrics

Corpus uses cosine distance (`vector_cosine_ops`, the `<=>` operator) because
sentence-embedding models are trained for cosine similarity. pgvector also
offers Euclidean distance (`vector_l2_ops`, `<->`) and negative inner product
(`vector_ip_ops`, `<#>`). If embeddings are normalized to unit length, cosine
and inner product produce identical rankings; cosine is the safe default when
you cannot guarantee normalization. The similarity score surfaced by Spring
AI is `1 - cosine_distance`, so 1.0 means identical direction.

## Metadata filtering with JSONB

Every chunk carries metadata: `user_id`, `document_id`, `filename`, and
`chunk_index`. Per-user isolation is enforced by filtering on
`metadata->>'user_id'` for SQL paths and by jsonpath predicates
(`metadata::jsonb @@ '$.user_id == "..."'`) generated from Spring AI filter
expressions on the vector path. A GIN index with the `jsonb_path_ops`
operator class accelerates the jsonpath form, and expression B-tree indexes on
`(metadata->>'user_id')` and `(metadata->>'document_id')` serve the SQL form.

## One table, two search legs

The same table serves both retrieval legs. A generated column
`content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('english', content))
STORED` with a GIN index powers PostgreSQL full-text search, while the
embedding column powers vector similarity. Keeping both in one row means the
two result lists share chunk UUIDs, which makes Reciprocal Rank Fusion a
simple key-based merge with no joins.

## Operational notes

The embedding dimension is part of the schema: switching from a 768-dimension
model to a 384-dimension model requires dropping and re-ingesting the store,
which is why Corpus ships a startup guard that compares the configured
dimension against `format_type` of the embedding column and refuses to boot on
mismatch. Backups are ordinary PostgreSQL backups — one more benefit of not
running a separate vector database.
